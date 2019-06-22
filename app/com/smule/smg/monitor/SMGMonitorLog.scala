package com.smule.smg.monitor

import java.io.{File, FileWriter}
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

import javax.inject.{Inject, Singleton}
import play.api.inject.ApplicationLifecycle
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGLogger
import com.smule.smg.remote.{SMGRemote, SMGRemotesApi}
import com.smule.smg.rrd.SMGRrd

/**
  * Created by asen on 7/15/16.
  */

@Singleton
class SMGMonitorLog  @Inject() (configSvc: SMGConfigService, remotes: SMGRemotesApi, lifecycle: ApplicationLifecycle)  extends SMGMonitorLogApi {

  val log = SMGLogger

  private def monlogBaseDir = configSvc.config.globals.getOrElse("$monlog_dir", "monlog")

  try {
    new File(monlogBaseDir).mkdirs()
  } catch {
    case t: Throwable => {
      log.ex(t, s"Unable to create monlog_dir: $monlogBaseDir")
    }
  }

  private val dateDirFmt = new SimpleDateFormat("/yyyy-MM-dd")

  private val recentLogs = ListBuffer[SMGMonitorLogMsg]()

  lifecycle.addStopHook { () =>
    Future.successful {
      saveOnShutdown()
    }
  }

  override def getLocal(flt: SMGMonitorLogFilter): Seq[SMGMonitorLogMsg] = {
    val periodSecs = SMGRrd.parsePeriod(flt.periodStr).getOrElse(3600)
    val curTss = SMGState.tssNow
    val startTss = curTss - periodSecs
    val lb = ListBuffer[List[SMGMonitorLogMsg]]()
    var ct = startTss
    while (ct <= curTss) {
      lb += loadLogFile(tsLogFn(ct))
      ct += 24 * 3600
    }
    // XXX there is a possible race condition here related to the most recent chunk
    // but better keep the synchronization to minimum to avoid putting a bottleneck on updates
    recentLogs.synchronized {
      lb += recentLogs.toList
    }
    lb.flatten.toList.filter { m =>
      (m.ts >= startTss) &&
        (flt.inclSoft || m.isHard) &&
        (flt.inclAcked || !m.isAcked) &&
        (flt.inclSilenced || !m.isSilenced) &&
        (flt.minSeverity.isEmpty ||
          m.curState.state >= flt.minSeverity.get ||
          (m.curState.state == SMGState.OK && m.prevState.map(_.state).getOrElse(SMGState.OK) >= flt.minSeverity.get) ) &&
        ((flt.rx.getOrElse("") == "") || SMGMonFilter.ciRegex(flt.rx).get.findFirstIn(m.msIdFmt).nonEmpty) &&
        ((flt.rxx.getOrElse("") == "") || SMGMonFilter.ciRegex(flt.rxx).get.findFirstIn(m.msIdFmt).isEmpty)
    }.reverse.take(flt.limit)
  }

  override def getAll(flt: SMGMonitorLogFilter): Future[Seq[SMGMonitorLogMsg]] = {
    implicit val myEc: ExecutionContext = configSvc.executionContexts.monitorCtx
    val futs = if (flt.rmtIds.isEmpty || flt.rmtIds.contains(SMGRemote.wildcard.id))
      Seq(Future { getLocal(flt) }) ++ configSvc.config.remotes.map { rmt =>
      remotes.monitorLogs(rmt.id, flt)
    } else {
      flt.rmtIds.map { rmtId =>
        if (rmtId == SMGRemote.local.id) {
          Future { getLocal(flt) }
        } else {
          remotes.monitorLogs(rmtId, flt)
        }
      }
    }
    Future.sequence(futs).map { seqs =>
      seqs.flatten.sortBy(- _.ts).take(flt.limit) // most recent first
    }
  }

  private def loadLogFile(fn:String): List[SMGMonitorLogMsg] = {
    try {
      if (new File(fn).exists()) {
        val src = Source.fromFile(fn)
        try {
          src.getLines().map { ln =>
            val ret = SMGMonitorLogMsg.parseLogLine(ln)
            if (ret.isEmpty && (ln != "")) log.error(s"SMGMonitorLog.loadLogFile($fn): bad line: " + ln)
            ret
          }.filter(_.isDefined).map(_.get).toList
        } finally {
          src.close()
        }
      } else List()
    } catch {
      case t: Throwable => {
        log.ex(t, s"Error loading log file: $fn")
        List()
      }
    }
  }

  private def tsToDate(ts: Int): Date = new Date(ts.toLong * 1000)

  private def tsLogFn(ts: Int): String = monlogBaseDir + dateDirFmt.format(tsToDate(ts)) + "-json.log"

  private def saveChunkToFile(fname: String, chunk: List[SMGMonitorLogMsg]): Unit = {
    try {
      log.debug(s"Saving chunk to file: $fname (chunk size=${chunk.size})")
      val fw = new FileWriter(fname, true)
      try {
        chunk.foreach { msg =>
          fw.write(msg.serialize.toString())
          fw.write("\n")
        }
      }
      finally fw.close()
      log.info(s"Saved chunk to file: $fname (chunk size=${chunk.size})")
    } catch {
      case t: Throwable => {
        log.ex(t, s"Error saving chunk (chunk.size=${chunk.size}) to file: $fname")
      }
    }
  }

  private def saveLogChunk(chunk: List[SMGMonitorLogMsg]): Unit = {
    // handle day roll-over
    chunk.groupBy(m => tsLogFn(m.ts)).foreach { t => saveChunkToFile(t._1, t._2) }
  }

  private def saveOnShutdown(): Unit = {
    recentLogs += SMGMonitorLogMsg(
      SMGState.tssNow, None,
      SMGState(SMGState.tssNow, SMGState.SMGERR, "SMG Shutdown"),
      None, 1, isHard = true, isAcked = false, isSilenced = false, Seq(), None, SMGRemote.local)
    saveLogChunk(recentLogs.toList)
  }

  override def logMsg(msg: SMGMonitorLogMsg): Unit = {
    log.debug("MONITOR_STATE_CHANGE: " + msg)
    recentLogs.synchronized {
      recentLogs += msg
    }
  }

  override def tick(): Unit = {
    // minimize synchronized time - actual save does not block adding new log msgs
    var toSave: Option[List[SMGMonitorLogMsg]] = None
    recentLogs.synchronized {
      toSave = Some(recentLogs.toList)
      recentLogs.clear()
    }
    if (toSave.nonEmpty && toSave.get.nonEmpty) {
      saveLogChunk(toSave.get)
    }
  }

}
