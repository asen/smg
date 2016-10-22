package com.smule.smg

import java.io.{File, FileWriter}
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}

import play.api.inject.ApplicationLifecycle

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.io.Source

/**
  * Created by asen on 7/15/16.
  */

/**
  * Case class representing a monitor log message which in turn is usually a state change
  * @param mltype - state change enum value
  * @param ts - timestamp (int, seconds)
  * @param msg - log message text
  * @param repeat - how many times we got a non-OK state
  * @param isHard - is the tranition "hard" (exceeded max repeats threshold, default 3)
  * @param ouids - optional relevant object id (can be more than one ofr pre-fetch errors)
  * @param vix - optional relevant object variable imdex
  * @param remote - originating remote instance
  */
case class SMGMonitorLogMsg(mltype: SMGMonitorLogMsg.Value,
                            ts: Int,
                            msg: String,
                            repeat: Int,
                            isHard: Boolean,
                            ouids: Seq[String],
                            vix: Option[Int],
                            remote: SMGRemote
                           ) {


  lazy val tsFmt = SMGMonitorLogMsg.logTsFmt.format(new Date(ts.toLong * 1000))
  lazy val ouidFmt = if (ouids.isEmpty) "-" else ouids.mkString(",")
  lazy val vixFmt = vix.map(_.toString).getOrElse("-")
  lazy val hardStr = if (isHard) "HARD" else "SOFT"

  lazy val logLine = s"[$tsFmt]: $ts $mltype $ouidFmt $vixFmt $repeat ${hardStr} $msg\n"

  def objectsFilter = SMGMonStateAgg.objectsUrlFilter(ouids)
  def objectsFilterWithRemote = s"remote=${remote.id}&$objectsFilter"
}


object SMGMonitorLogMsg extends Enumeration {
  type SMGMonitorLogMsg = Value
  val RECOVERY, CNTROVRF, ANOMALY, WARNING, FETCHERR, CRITICAL, OVERLAP, SMGERR = Value

  def fromObjectState(os: SMGState.Value) = {
    os match {
      case SMGState.OK => this.RECOVERY
      case SMGState.E_VAL_WARN => this.WARNING
      case SMGState.E_VAL_CRIT => this.CRITICAL
      case SMGState.E_FETCH => this.FETCHERR
      case SMGState.E_ANOMALY => this.ANOMALY
      case _ => this.SMGERR
    }
  }

  val logTsFmt = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZ")

  def parseLogLine(ln: String): Option[com.smule.smg.SMGMonitorLogMsg] = {
    val arr = ln.split(" ", 8)
    if (arr.length != 8)
      None
    else {
      Some(
        SMGMonitorLogMsg(
          SMGMonitorLogMsg.withName(arr(2)),
          Integer.parseInt(arr(1)), arr(7),
          Integer.parseInt(arr(5)), arr(6) == "HARD",
          if (arr(3) == "-") Seq() else arr(3).split(",").toSeq,
          if (arr(4) == "-") None else Some(Integer.parseInt(arr(4))),
          SMGRemote.local
        )
      )
    }
  }
}

trait SMGMonitorLogApi {
  /**
    * log a state change
    * @param msg - msg representing the state change
    */
  def logMsg(msg: SMGMonitorLogMsg): Unit

  /**
    * Get all local logs since given period
    * @param periodStr - period string
    * @param limit - max entries to return
    * @param hardOnly - whether to include soft errors or hard only
    * @return
    */
  def getLocal(periodStr: String, limit: Int, hardOnly: Boolean): Seq[SMGMonitorLogMsg]

  /**
    * Get all logs since given period (from all remotes)
    * @param periodStr - period string
    * @param limit - max entries to return
    * @param hardOnly - whether to include soft errors or hard only
    * @return
    */
  def getSince(periodStr: String, limit: Int, hardOnly: Boolean): Future[Seq[SMGMonitorLogMsg]]
}

@Singleton
class SMGMonitorLog  @Inject() (configSvc: SMGConfigService, remotes: SMGRemotesApi, lifecycle: ApplicationLifecycle)  extends SMGMonitorLogApi {

  val log = SMGLogger

  private def monlogBaseDir = configSvc.config.globals.getOrElse("$monlog_dir", "monlog")

  new File(monlogBaseDir).mkdirs()

  private val dateDirFmt = new SimpleDateFormat("/yyyy-MM-dd")

  private val RECENTS_MAX_SIZE = 2000


  private val recentLogs = ListBuffer[SMGMonitorLogMsg]()

  lifecycle.addStopHook { () =>
    Future.successful {
      saveOnShutdown()
    }
  }

  override def getLocal(periodStr: String, limit: Int, hardOnly: Boolean): Seq[SMGMonitorLogMsg] = {
    val periodSecs = SMGRrd.parsePeriod(periodStr).getOrElse(3600)
    val curTss = SMGState.tssNow
    val startTss = curTss - periodSecs
    val lb = ListBuffer[List[SMGMonitorLogMsg]]()
    var ct = startTss
    while (ct <= curTss) {
      lb += loadLogFile(tsLogFn(ct))
      ct += 24 * 3600
    }
    lb += recentLogs.toList
    lb.flatten.toList.reverse.filter(m => (m.ts >= startTss) && ((!hardOnly) || m.isHard)).take(limit)
  }


  override def getSince(periodStr: String, limit: Int, hardOnly: Boolean): Future[Seq[SMGMonitorLogMsg]] = {
    implicit val myEc = ExecutionContexts.monitorCtx
    val remoteFuts = configSvc.config.remotes.map { rmt =>
      remotes.monitorLogs(rmt.id, periodStr, limit, hardOnly)
    }
    val localFut = Future {
      getLocal(periodStr, limit, hardOnly)
    }
    val allFuts = Seq(localFut) ++ remoteFuts
    Future.sequence(allFuts).map { seqs =>
      seqs.flatten.sortBy(- _.ts).take(limit) // most recent first
    }
  }

  private def loadLogFile(fn:String): List[SMGMonitorLogMsg] = {
    if (new File(fn).exists()) {
      Source.fromFile(fn).getLines().map { ln =>
        val ret = SMGMonitorLogMsg.parseLogLine(ln)
        if (ret.isEmpty && (ln != "")) log.error(s"SMGMonitorLog.loadLogFile($fn): bad line: " + ln)
        ret
      }.filter(_.isDefined).map(_.get).toList
    } else List()
  }

  private def tsToDate(ts: Int): Date = new Date(ts.toLong * 1000)

  private def tsLogFn(ts: Int): String = monlogBaseDir + dateDirFmt.format(tsToDate(ts)) + ".log"

  private def saveChunkToFile(fname: String, chunk: List[SMGMonitorLogMsg]): Unit = {
    log.debug(s"Saving chunk to file: $fname (chunk size=${chunk.size})")
    val fw = new FileWriter(fname, true)
    try {
      chunk.foreach { msg =>
        fw.write(msg.logLine.replaceAll("\\n", " "))
        fw.write("\n")
      }
    }
    finally fw.close()
    log.info(s"Saved chunk to file: $fname (chunk size=${chunk.size})")
  }

  private def saveLogChunk(chunk: List[SMGMonitorLogMsg]): Unit = {
    // handle day roll-over
    chunk.groupBy(m => tsLogFn(m.ts)).foreach { t => saveChunkToFile(t._1, t._2) }
  }

  private def saveOnShutdown() = {
    recentLogs += SMGMonitorLogMsg(
      SMGMonitorLogMsg.SMGERR,
      SMGState.tssNow,
      "SMG shutdown", 1, true, Seq(), None, SMGRemote.local)
    saveLogChunk(recentLogs.toList)
  }

  private val logFlushIsRunning = new AtomicBoolean(false)

  override def logMsg(msg: SMGMonitorLogMsg): Unit = {
    log.debug("MONITOR_STATE_CHANGE: " + msg)
    recentLogs += msg
    Future {
      val sz = recentLogs.size
      if (sz >= RECENTS_MAX_SIZE) {
        if (logFlushIsRunning.compareAndSet(false, true)) try {
          // only one thread can get here
          val toSave = recentLogs.toList
          recentLogs.remove(0, toSave.size)
          saveLogChunk(toSave)
        } finally {
          logFlushIsRunning.set(false)
        }
      }
    } (ExecutionContexts.monitorCtx) //TODO use diff ctx?
  }

}