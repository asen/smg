package com.smule.smg.monitor

import java.net.URLEncoder
import java.text.{DecimalFormat, SimpleDateFormat}
import java.util.Date

import play.api.libs.json.{JsPath, JsValue, Json, Reads}
import com.smule.smg._
import com.smule.smg.core._
import com.smule.smg.remote._


object SMGMonState {

  val MON_STATE_GLOBAL_PX = "_global_"

  def severityStr(severity: Double): String = {
    SMGState.stateChar(severity.round.toInt)
  }

  def textColor(severity: Double): String = SMGState.stateTextColor(severity.round.toInt)

  def oidFilter(oid: String): String = {
    s"rx=^${SMGRemote.localId(oid)}$$"
  }
}


trait SMGMonState extends SMGTreeNode {

  //val id: String

  def severity: Double
  def text: String
  def isHard: Boolean
  def isAcked: Boolean
  def isSilenced: Boolean
  def silencedUntil: Option[Int]
  def oid: Option[String]
  def pfId: Option[String]
  def aggShowUrlFilter: Option[String]
  def remote : SMGRemote

  def recentStates: Seq[SMGState]

  def getLocalMatchingIndexes: Seq[SMGIndex]

  def errorRepeat: Int

  def alertKey: String
  def alertSubject: String = alertKey // can be overriden

  def currentStateVal: SMGState.Value = recentStates.headOption.map(_.state).getOrElse(SMGState.SMGERR) // XXX empty recentStates is smg err

  private def urlPx = "/dash?remote=" + java.net.URLEncoder.encode(remote.id, "UTF-8") + "&"
  private def dataUrlPx = "/monitor/svgDataJson?remote=" + java.net.URLEncoder.encode(remote.id, "UTF-8") + "&"

  private def myShowUrlFilter: Option[String] = if (aggShowUrlFilter.isDefined) {
    aggShowUrlFilter
  } else if (oid.isDefined) {
    Some(SMGMonState.oidFilter(oid.get))
  } else None

  def dataUrl: String = myShowUrlFilter.map { flt =>
    dataUrlPx + flt
  }.getOrElse(dataUrlPx)

  def showUrl: String = myShowUrlFilter.map { flt =>
    urlPx + flt
  }.getOrElse("/monitor")

  def isOk: Boolean = currentStateVal == SMGState.OK
  def severityStr: String = SMGMonState.severityStr(severity)

  def severityColor: String = {
    if (severity == 0.0)
      SMGState.okColor
    else {
      val ix = SMGState.colorsSeq.indexWhere(t => severity < t._1)
      if (ix < 0)
        SMGState.colorsSeq.last._2
      else
        SMGState.colorsSeq(ix)._2
    }
  }

  def textColor: String = SMGMonState.textColor(severity)

  def hardStr: String = if (isOk) "" else if (isHard) " HARD" else " SOFT"

  def silencedUntilStr: String = if (silencedUntil.isEmpty) "permanently" else {
    "until " + new Date(silencedUntil.get.toLong * 1000).toString
  }

  def isSilencedOrAcked: Boolean = isSilenced || isAcked

  def notifySubject(smgHost: String, smgRemoteId: Option[String], isRepeat: Boolean): String = {
    val repeatStr = if (isRepeat) "(repeat) " else ""
    s"${SMGMonNotifySeverity.fromStateValue(currentStateVal)}: ${smgRemoteId.map(rid => s"($rid) ").getOrElse("")}$repeatStr$alertSubject"
  }

  private def bodyLink(smgBaseUrl: String, smgRemoteId: Option[String]) = if (myShowUrlFilter.isEmpty)
    s"$smgBaseUrl/monitor#rt_${URLEncoder.encode(smgRemoteId.getOrElse(SMGRemote.local.id), "UTF-8")}"
  else
    s"$smgBaseUrl/dash?remote=${URLEncoder.encode(smgRemoteId.getOrElse(SMGRemote.local.id), "UTF-8")}&${myShowUrlFilter.get}"

  def notifyBody(smgBaseUrl: String, smgRemoteId: Option[String]): String = {
    val ret = s"Remote: ${smgRemoteId.getOrElse(SMGRemote.localName)}\n\n" +
      s"Message: $text\n\n" +
      s"Object(s) link: ${bodyLink(smgBaseUrl, smgRemoteId)}\n\n"
    val maxIxes = 60 //sane limit
    val ixes = getLocalMatchingIndexes
    val ixesTxt = if (ixes.isEmpty) {
      ""
    } else {
      "Relevant index links:\n\n" +
      ixes.take(maxIxes).map { ix =>
        val remoteUrl = s"$smgBaseUrl/dash?ix=" + smgRemoteId.map(rmt => s"@$rmt.").getOrElse("") + ix.id
        s"- ${ix.title} [ $remoteUrl ]"
      }.mkString("\n") + "\n\n" + (if (ixes.lengthCompare(maxIxes) > 0) "(list truncated ...)\n\n" else "")
    }
    ret + ixesTxt
  }

  def asJson: JsValue = {
    import  SMGRemoteClient.smgMonStateWrites
    Json.toJson(this)
  }

}

