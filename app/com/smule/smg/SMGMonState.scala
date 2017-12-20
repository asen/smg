package com.smule.smg

import java.net.URLEncoder
import java.text.{DecimalFormat, SimpleDateFormat}
import java.util.Date

import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, JsValue, Json, Reads}

object SMGState extends Enumeration {

  // Sorted by severity
  val OK, ANOMALY, WARNING, UNKNOWN, CRITICAL, SMGERR = Value

  val hmsTimeFormat = new SimpleDateFormat("HH:mm:ss")

  val shortTimeFormat = new SimpleDateFormat("MMM d HH:mm:ss")

  val longTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  val YEAR_SECONDS: Int = 365 * 24 * 3600  //roughly
  val DAY_SECONDS: Int = 24 * 3600

  /**
    * withName is final so can not be overriden - using this to allow backwards compatibility with old names
    * calls withName unless known old name
    * @param nm - the string value name
    * @return
    */
  def fromName(nm: String): SMGState.Value = {
    // XXX backwards compatibility with old value names
    nm match {
      case "E_ANOMALY" => this.ANOMALY
      case "E_VAL_WARN" => this.WARNING
      case "E_FETCH" => this.UNKNOWN
      case "E_VAL_CRIT" => this.CRITICAL
      case "E_SMGERR" => this.SMGERR
      case _ => this.withName(nm)
    }
  }

  def formatTss(ts: Int): String = {
    val tsDiff = Math.abs(tssNow - ts)
    val myFmt = if (tsDiff < DAY_SECONDS)
      hmsTimeFormat
    else  if (tsDiff < YEAR_SECONDS)
      shortTimeFormat
    else
      longTimeFormat
    myFmt.format(new Date(ts.toLong * 1000))
  }

  def formatDuration(duration: Int): String = {
    var tsDiff = duration
    val sb = new StringBuilder()
    if (tsDiff >  YEAR_SECONDS) {
      sb.append(s"${tsDiff / YEAR_SECONDS}y")
      tsDiff = tsDiff % YEAR_SECONDS
    }
    if (tsDiff > DAY_SECONDS) {
      sb.append(s"${tsDiff / DAY_SECONDS}d")
      tsDiff = tsDiff % DAY_SECONDS
    }
    if (tsDiff > 3600) {
      sb.append(s"${tsDiff / 3600}h")
      tsDiff = tsDiff % 3600
    }
    sb.append(s"${tsDiff / 60}m")
    sb.toString()
  }

  private val mySmallFormatter = new DecimalFormat("#.######")
  private val myBigFormatter = new DecimalFormat("#.###")

  def numFmt(num: Double, mu: Option[String]): String = if (num.isNaN) "NaN" else {
    val absNum = math.abs(num)
    val (toFmt, metricPrefix, myFormatter) = if (absNum >= 1000000000) {
      (num / 1000000000, "G", myBigFormatter)
    } else if (absNum >= 1000000) {
      (num / 1000000, "M", myBigFormatter)
    } else if (absNum >= 1000) {
      (num / 1000, "K", myBigFormatter)
    } else if (absNum >= 1) {
      (num, "", myBigFormatter)
    } else {
      (num, "", mySmallFormatter)
    }
    myFormatter.format(toFmt) + metricPrefix + mu.getOrElse("")
  }


  private val severityChars = Map(
    0 -> "",   // OK
    1 -> "~",  // ANOMALY
    2 -> "^",  // WARNING
    3 -> "?",  // UNKNOWN
    4 -> "!",  // CRITICAL
    5 -> "e"   // SMGERR
  )

  private val severityTextColors = Map(
    0 -> "white",  // OK
    1 -> "black",  // ANOMALY
    2 -> "black",  // WARNING
    3 -> "white",  // UNKNOWN
    4 -> "white",  // CRITICAL
    5 -> "white"   // SMGERR
  )

  def stateChar(stateIx: Int): String = severityChars.getOrElse(stateIx, "e")

  def stateTextColor(stateIx: Int): String = severityTextColors.getOrElse(stateIx, "black")

  val okColor = "#009900"
  val colorsSeq = Seq(
    0.5 -> "#00e600",
    1.0 -> "#33cccc",
    1.5 -> "#0099cc",
    2.0 -> "#e6e600",
    2.5 -> "#ffff00",
    3.0 -> "#ff9900",
    3.5 -> "#cc7a00",
    4.0 -> "#ff3300",
    5.0 -> "#ff0000"
  )

  val colorsMap: Map[Double, String] = (Seq(0.0 -> okColor) ++ colorsSeq).toMap

  def stateColor(stateIx: Int): String =  colorsMap.getOrElse(stateIx.toDouble, "#000000")

  def tssNow: Int = SMGRrd.tssNow //(System.currentTimeMillis() / 1000).toInt

  val smgStateReads: Reads[SMGState] = {
    //SMGState(ts: Int, state: SMGState.Value, desc: String)
    (
      (JsPath \ "ts").read[Int] and
        (JsPath \ "state").read[String].map(s => SMGState.fromName(s)) and
        (JsPath \ "desc").read[String]
      ) (SMGState.apply _)
  }

  def initialState = SMGState(SMGState.tssNow, SMGState.OK, "Initial state")
}

case class SMGState(ts: Int, state: SMGState.Value, desc: String) {
  def timeStr: String = SMGState.formatTss(ts)
  def charRep: String = SMGState.stateChar(state.id)
  def stateColor: String = SMGState.stateColor(state.id)
  def textColor: String = SMGState.stateTextColor(state.id)
  val isOk: Boolean = state == SMGState.OK
}


object SMGMonState {

  val MON_STATE_GLOBAL_PX = "_global_"

  def severityStr(severity: Double): String = {
    SMGState.stateChar(severity.round.toInt)
  }

  def textColor(severity: Double): String = SMGState.stateTextColor(severity.round.toInt)

  def oidFilter(oid: String): String = {
    val arr = SMGRemote.localId(oid).split("\\.")
    val optDot = if (arr.length > 1) "." else ""
    s"px=${arr.dropRight(1).mkString(".") + optDot}&sx=${optDot + arr.lastOption.getOrElse("")}"
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

  private def myShowUrlFilter: Option[String] = if (aggShowUrlFilter.isDefined) {
    aggShowUrlFilter
  } else if (oid.isDefined) {
    Some(SMGMonState.oidFilter(oid.get))
  } else None

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

// "generic"/remote mon state
case class SMGMonStateView(id: String,
                           severity: Double,
                           text: String,
                           isHard: Boolean,
                           isAcked: Boolean,
                           isSilenced: Boolean,
                           silencedUntil: Option[Int],
                           oid: Option[String],
                           pfId: Option[String],
                           parentId: Option[String],
                           aggShowUrlFilter: Option[String],
                           recentStates: Seq[SMGState],
                           errorRepeat: Int,
                           remote: SMGRemote
                          ) extends SMGMonState {

  override def alertKey: String = id

  override def getLocalMatchingIndexes: Seq[SMGIndex] = {
    // TODO XXX this should never be called ... throw a runtime error instead? For now just log
    // may have to do some refactoring to fix that
    SMGLogger.error(s"getLocalMatchingIndexes called for SMGMonStateView($id)")
    Seq()
  }
}

// local agg (condensed) mon state
case class SMGMonStateAgg(id: String, lst: Seq[SMGMonState], showUrlFilter: String) extends SMGMonState {

  lazy val lstsz: Int = lst.size

  override val severity: Double =  if (lstsz == 0) 0 else lst.map(_.severity).max
  override def text: String = {
    val lsz = lst.size
    if (lsz > 10)
      s"Multiple ($lsz) objects including: \n" + lst.take(5).map(ms => ms.text + ms.hardStr).mkString("\n") + " ..."
    else
      lst.map(ms => ms.text + ms.hardStr).mkString("\n")
  }
  override val isHard: Boolean = lst.exists(_.isHard)             // at least one hard means hard
  override val isAcked: Boolean = lst.forall(_.isAcked)
  override val isSilenced: Boolean = lst.forall(_.isSilenced)
  private val minSilencedUntil = lst.map(ovs => ovs.silencedUntil.getOrElse(Int.MaxValue)).min
  override val silencedUntil: Option[Int] = if (minSilencedUntil == Int.MaxValue) None else Some(minSilencedUntil)
  override val oid: Option[String] = None
  override val pfId: Option[String] = None
  override val aggShowUrlFilter = Some(showUrlFilter)

  override val errorRepeat: Int = lst.map(_.errorRepeat).max
  //  override lazy val hardStr = ""
  override val remote: SMGRemote = SMGRemote.local

  override val recentStates: Seq[SMGState] = {
    val longestListState = lst.maxBy(_.recentStates.size)
    longestListState.recentStates.indices.map { i =>
      val statesAtI = lst.map(ms => ms.recentStates.lift(i)).collect { case Some(x) => x }
      statesAtI.maxBy(_.state)
    }
  }

  // XXX chop off :ix portion of child alert keys to define this alert key.
  // Agg states cover entire objects in the context of alerting so var indexes are thrown away
  override def alertKey: String = lst.map(_.alertKey.split(":")(0)).distinct.mkString(",")

  override def parentId: Option[String] = None

  override def getLocalMatchingIndexes: Seq[SMGIndex] =
    lst.flatMap(_.getLocalMatchingIndexes ).sortBy(_.title).distinct
}

object SMGMonStateAgg {

  def objectsUrlFilter(oids: Seq[String]): String = {
    if (oids.isEmpty)
      return "rx=ERROR"
    if (oids.tail.isEmpty)
      return SMGMonState.oidFilter(oids.head)
    val rx = s"^(${oids.map(oid => SMGRemote.localId(oid)).distinct.mkString("|")})$$"
    "rx=" + java.net.URLEncoder.encode(rx, "UTF-8") // TODO, better showUrl?
  }

  def aggByParentId(lst: Seq[SMGMonState]): Map[String, SMGMonState]= {
    val ret = lst.filter(_.parentId.isDefined).groupBy(_.parentId.get).map { t =>
      val pid = t._1
      val msa = SMGMonStateAgg(pid, t._2, s"rx=$pid")
      (pid,msa)
    }
    ret
  }
}

case class SMGMonStateGlobal(title: String,
                             label: String,
                             currentState: SMGState
                            ) extends SMGMonState{

  //  def currentState = recentStates.head
  val id: String = label

  val desc = s"$title ($label)"

  def longDesc(s: SMGState) = s"$desc: ${s.desc} (ts=${s.timeStr})"

  lazy val severity: Double = currentState.state.id.toDouble
  def text: String = longDesc(currentState)
  lazy val isHard = true
  override val isAcked = false // TODO
  override val isSilenced = false // TODO
  override val silencedUntil: Option[Int] = None
  override val aggShowUrlFilter: Option[String] = None
  override val oid: Option[String] = None
  override val pfId: Option[String] = None

  override val remote: SMGRemote = SMGRemote.local

  override val errorRepeat  = 1

  override def alertKey = s"${SMGMonState.MON_STATE_GLOBAL_PX}$label"

  override def recentStates: Seq[SMGState] = Seq(currentState)

  override def parentId: Option[String] = None

  override def getLocalMatchingIndexes: Seq[SMGIndex] = {
    Seq()
  }
}
