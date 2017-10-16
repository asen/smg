package com.smule.smg

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

/**
  * Created by asen on 7/15/16.
  */

/**
  * Case class representing a monitor log message which in turn is usually a state change
  * @param ts - timestamp (int, seconds)
  * @param repeat - how many times we got a non-OK state
  * @param isHard - is the tranition "hard" (exceeded max repeats threshold, default 3)
  * @param ouids - optional relevant object id (can be more than one ofr pre-fetch errors)
  * @param vix - optional relevant object variable imdex
  * @param remote - originating remote instance
  */
case class SMGMonitorLogMsg(ts: Int,
                            msid: Option[String],
                            curState: SMGState,
                            prevState: Option[SMGState],
                            repeat: Int,
                            isHard: Boolean,
                            isAcked: Boolean,
                            isSilenced: Boolean,
                            ouids: Seq[String],
                            vix: Option[Int],
                            remote: SMGRemote
                           ) {


  val mltype: SMGMonitorLogMsg.Value = SMGMonitorLogMsg.fromObjectState(curState.state)

  val msg = s"state: ${curState.desc} (prev state: ${prevState.map(_.desc).getOrElse("N/A")})"

  lazy val tsFmt: String = tsFmt(ts)

  def hourTs: Int = SMGMonitorLogMsg.hourTs(ts)

  def tsFmt(t :Int): String = SMGMonitorLogMsg.tsFmt(t)

  lazy val ouidFmt: String = if (ouids.isEmpty) "-" else ouids.mkString(",")
  lazy val msIdFmt: String = msid.getOrElse(ouidFmt)

  lazy val vixFmt: String = vix.map(_.toString).getOrElse("-")
  lazy val hardStr: String = if (isHard) "HARD" else "SOFT"

  lazy val logLine = s"[$tsFmt]: $ts $mltype $msIdFmt $vixFmt $repeat $hardStr $msg\n"

  def objectsFilter: String = SMGMonStateAgg.objectsUrlFilter(ouids)
  def objectsFilterWithRemote = s"remote=${java.net.URLEncoder.encode(remote.id, "UTF-8")}&$objectsFilter"

  def serialize: JsValue = {
    implicit val monLogWrites = SMGRemoteClient.smgMonitorLogWrites
    Json.toJson(this)
  }
}


object SMGMonitorLogMsg extends Enumeration {
  type SMGMonitorLogMsg = Value
  val RECOVERY, ANOMALY, WARNING, UNKNOWN, CRITICAL, OVERLAP, SMGERR = Value

  def fromObjectState(os: SMGState.Value) = {
    os match {
      case SMGState.OK => this.RECOVERY
      case SMGState.ANOMALY => this.ANOMALY
      case SMGState.WARNING => this.WARNING
      case SMGState.UNKNOWN => this.UNKNOWN
      case SMGState.CRITICAL => this.CRITICAL
      case _ => this.SMGERR
    }
  }

  val logTsFmt = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZ")

  def tsFmt(t :Int): String = logTsFmt.format(new Date(t.toLong * 1000))

  def hourTs(ts: Int): Int = (ts / 3600) * 3600

  def smgMonitorLogMsgReads(remote: SMGRemote): Reads[com.smule.smg.SMGMonitorLogMsg] = {
    implicit val smgStateReads = SMGState.smgStateReads

    def myPrefixedId(s: String) = {
      SMGRemote.prefixedId(remote.id, s)
    }
    (
      (JsPath \ "ts").read[Int] and
        (JsPath \ "msid").readNullable[String].map(opt => opt.map(myPrefixedId)) and
        (JsPath \ "cs").read[SMGState] and
        (JsPath \ "ps").readNullable[SMGState] and
        (JsPath \ "rpt").read[Int] and
        (JsPath \ "hard").readNullable[String].map(hopt => hopt.getOrElse("") == "true") and
        (JsPath \ "ackd").readNullable[String].map(opt => opt.getOrElse("") == "true") and
        (JsPath \ "slcd").readNullable[String].map(opt => opt.getOrElse("") == "true") and
        (JsPath \ "ouids").readNullable[List[String]].map(ol => ol.getOrElse(Seq()).map(s => myPrefixedId(s))) and
        (JsPath \ "vix").readNullable[Int] and
        // XXX "remote" is always null ...
        (JsPath \ "remote").readNullable[String].map(s => remote)
      ) (SMGMonitorLogMsg.apply _)
  }

  implicit val logMsgreads = smgMonitorLogMsgReads(SMGRemote.local)

  def parseLogLine(ln: String): Option[com.smule.smg.SMGMonitorLogMsg] = {
    try {
      Some(Json.parse(ln).as[com.smule.smg.SMGMonitorLogMsg])
    } catch {
      case x: Throwable => {
        SMGLogger.ex(x, s"Unexpected error parsing log msg: $ln")
        None
      }
    }
  }
}

case class SMGMonitorLogFilter(periodStr: String, rmtIds: Seq[String], limit: Int, minSeverity: Option[SMGState.Value],
                               inclSoft: Boolean, inclAcked: Boolean, inclSilenced: Boolean,
                               rx: Option[String], rxx: Option[String])

trait SMGMonitorLogApi {
  /**
    * log a state change
    * @param msg - msg representing the state change
    */
  def logMsg(msg: SMGMonitorLogMsg): Unit

  /**
    * Get all local logs matching filter
    * @param flt - filter object
    * @return
    */
  def getLocal(flt: SMGMonitorLogFilter): Seq[SMGMonitorLogMsg]

  /**
    * Get all logs matching the filter (from all remotes)
    * @param flt
    * @return
    */
  def getAll(flt: SMGMonitorLogFilter): Future[Seq[SMGMonitorLogMsg]]

  /**
    * Called by scheduler to flush logs to disc asynchronously
    */
  def tick():Unit

}

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
    implicit val myEc: ExecutionContext = ExecutionContexts.monitorCtx
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
        Source.fromFile(fn).getLines().map { ln =>
          val ret = SMGMonitorLogMsg.parseLogLine(ln)
          if (ret.isEmpty && (ln != "")) log.error(s"SMGMonitorLog.loadLogFile($fn): bad line: " + ln)
          ret
        }.filter(_.isDefined).map(_.get).toList
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
