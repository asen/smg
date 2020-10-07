package com.smule.smg.monitor

import java.text.SimpleDateFormat
import java.util.Date

import com.smule.smg.core.SMGLogger
import com.smule.smg.remote.{SMGRemote, SMGRemoteClient}
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, JsValue, Json, Reads}

/**
  * Case class representing a monitor log message which in turn is usually a state change
  *
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
  val RECOVERY, ANOMALY, WARNING, FAILED, CRITICAL, OVERLAP, SMGERR = Value

  def fromObjectState(os: SMGState.Value) = {
    os match {
      case SMGState.OK => this.RECOVERY
      case SMGState.ANOMALY => this.ANOMALY
      case SMGState.WARNING => this.WARNING
      case SMGState.FAILED => this.FAILED
      case SMGState.CRITICAL => this.CRITICAL
      case _ => this.SMGERR
    }
  }

  val logTsFmt = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZ")

  def tsFmt(t :Int): String = logTsFmt.format(new Date(t.toLong * 1000))

  def hourTs(ts: Int): Int = (ts / 3600) * 3600

  def smgMonitorLogMsgReads(remote: SMGRemote): Reads[com.smule.smg.monitor.SMGMonitorLogMsg] = {
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

  def parseLogLine(ln: String): Option[com.smule.smg.monitor.SMGMonitorLogMsg] = {
    try {
      Some(Json.parse(ln).as[com.smule.smg.monitor.SMGMonitorLogMsg])
    } catch {
      case x: Throwable => {
        SMGLogger.ex(x, s"Unexpected error parsing log msg: $ln")
        None
      }
    }
  }
}
