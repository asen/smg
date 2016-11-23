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
import scala.concurrent.Future
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

  def hourTs = (ts / 3600) * 3600

  def tsFmt(t :Int): String = SMGMonitorLogMsg.logTsFmt.format(new Date(t.toLong * 1000))

  lazy val ouidFmt = if (ouids.isEmpty) "-" else ouids.mkString(",")
  lazy val msIdFmt = msid.getOrElse(ouidFmt)

  lazy val vixFmt = vix.map(_.toString).getOrElse("-")
  lazy val hardStr = if (isHard) "HARD" else "SOFT"

  lazy val logLine = s"[$tsFmt]: $ts $mltype $msIdFmt $vixFmt $repeat ${hardStr} $msg\n"

  def objectsFilter = SMGMonStateAgg.objectsUrlFilter(ouids)
  def objectsFilterWithRemote = s"remote=${remote.id}&$objectsFilter"

  def serialize: JsValue = {
    implicit val monLogWrites = SMGRemoteClient.smgMonitorLogWrites
    Json.toJson(this)
  }
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

  def smgMonitorLogMsgReads(remote: SMGRemote): Reads[com.smule.smg.SMGMonitorLogMsg] = {
    implicit val smgStateReads = SMGState.smgStateReads

    def myPrefixedId(s: String) = {
      if (remote.id == "") s else SMGRemote.prefixedId(remote.id, s)
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
    * @param inclSoft - whether to include soft errors or hard only
    * @param inclAcked- whether to include acked errors
    * @param inclSilenced - whether to include silenced errors
    * @return
    */
  def getLocal(periodStr: String, limit: Int, minSeverity: Option[SMGState.Value],
               inclSoft: Boolean, inclAcked: Boolean, inclSilenced: Boolean): Seq[SMGMonitorLogMsg]

  /**
    * Get all logs since given period (from all remotes)
    * @param periodStr - period string
    * @param limit - max entries to return
    * @param inclSoft - whether to include soft errors or hard only
    * @param inclAcked- whether to include acked errors
    * @param inclSilenced - whether to include silenced errors
    * @return
    */
  def getSince(periodStr: String, rmtOpt:Option[String], limit: Int, minSeverity: Option[SMGState.Value],
               inclSoft: Boolean, inclAcked: Boolean, inclSilenced: Boolean): Future[Seq[SMGMonitorLogMsg]]
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

  override def getLocal( periodStr: String, limit: Int,
                         minSeverity: Option[SMGState.Value], inclSoft: Boolean,
                         inclAcked: Boolean, inclSilenced: Boolean): Seq[SMGMonitorLogMsg] = {
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
    lb.flatten.toList.reverse.filter { m =>
      (m.ts >= startTss) &&
        (inclSoft || m.isHard) &&
        (inclAcked || !m.isAcked) &&
        (inclSilenced || !m.isSilenced) &&
        (minSeverity.isEmpty ||
          m.curState.state >= minSeverity.get ||
          (m.curState.state == SMGState.OK && m.prevState.map(_.state).getOrElse(SMGState.OK) >= minSeverity.get) )
    }.take(limit)
  }


  override def getSince(periodStr: String, rmtOpt:Option[String], limit: Int,
                        minSeverity: Option[SMGState.Value], inclSoft: Boolean,
                        inclAcked: Boolean, inclSilenced: Boolean): Future[Seq[SMGMonitorLogMsg]] = {
    implicit val myEc = ExecutionContexts.monitorCtx
    val remoteFuts = if (rmtOpt.isEmpty || rmtOpt.get == SMGRemote.wildcard.id) configSvc.config.remotes.map { rmt =>
      remotes.monitorLogs(rmt.id, periodStr, limit, minSeverity, inclSoft, inclAcked, inclSilenced)
    } else Seq(remotes.monitorLogs(rmtOpt.get, periodStr, limit, minSeverity, inclSoft, inclAcked, inclSilenced))
    val localFut = if (rmtOpt.getOrElse(SMGRemote.local.id) == SMGRemote.local.id ) Future {
      getLocal(periodStr, limit, minSeverity, inclSoft, inclAcked, inclSilenced)
    } else Future { Seq() }

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

  private def tsLogFn(ts: Int): String = monlogBaseDir + dateDirFmt.format(tsToDate(ts)) + "-json.log"

  private def saveChunkToFile(fname: String, chunk: List[SMGMonitorLogMsg]): Unit = {
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
  }

  private def saveLogChunk(chunk: List[SMGMonitorLogMsg]): Unit = {
    // handle day roll-over
    chunk.groupBy(m => tsLogFn(m.ts)).foreach { t => saveChunkToFile(t._1, t._2) }
  }

  private def saveOnShutdown() = {
    recentLogs += SMGMonitorLogMsg(
      SMGState.tssNow, None,
      SMGState(SMGState.tssNow, SMGState.E_SMGERR, "SMG Shutdown"),
      None, 1, isHard = true, isAcked = false, isSilenced = false, Seq(), None, SMGRemote.local)
    saveLogChunk(recentLogs.toList)
  }

  private val logFlushIsRunning = new AtomicBoolean(false)

  override def logMsg(msg: SMGMonitorLogMsg): Unit = {
    log.debug("MONITOR_STATE_CHANGE: " + msg)
    // TODO synchronization?
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