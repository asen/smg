package com.smule.smg

import java.text.SimpleDateFormat
import java.util.Date

import scala.concurrent.Future


/**
  * Created by asen on 7/6/16.
  */

object SMGState extends Enumeration {

  // Sorted by severity
  val OK, E_ANOMALY, E_VAL_WARN, E_FETCH, E_VAL_CRIT, E_SMGERR = Value

  val hmsTimeFormat = new SimpleDateFormat("HH:mm:ss")

  private val severityChars = Map(
    0 -> "",   //OK
    1 -> "~",  //E_ANOMALY
    2 -> "^",  //E_VAL_WARN
    3 -> "?",  //E_FETCH
    4 -> "!",   //E_VAL_CRIT
    5 -> "e"  //E_SMGERR
  )

  private val severityTextColors = Map(
    0 -> "white",   //OK
    1 -> "black",  //E_ANOMALY
    2 -> "black",  //E_VAL_WARN
    3 -> "white",  //E_FETCH
    4 -> "white",   //E_VAL_CRIT
    5 -> "white"  //E_SMGERR
  )

  def stateChar(stateIx: Int) = severityChars.getOrElse(stateIx, "e")

  def stateTextColor(stateIx: Int) = severityTextColors.getOrElse(stateIx, "black")

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

  val colorsMap = (Seq(0.0 -> okColor) ++ colorsSeq).toMap

  def stateColor(stateIx: Int) =  colorsMap.getOrElse(stateIx.toDouble, "#000000")

  def tssNow = SMGRrd.tssNow //(System.currentTimeMillis() / 1000).toInt
}

case class SMGState(ts: Int, state: SMGState.Value, desc: String) {
  def timeStr = SMGState.hmsTimeFormat.format(new Date(ts.toLong * 1000))
  def charRep = SMGState.stateChar(state.id)
  def stateColor = SMGState.stateColor(state.id)
  def textColor = SMGState.stateTextColor(state.id)
}


object SMGMonState {

  val MON_STATE_GLOBAL_PX = "_global_"

  def severityStr(severity: Double): String = {
    SMGState.stateChar(severity.round.toInt)
  }

  def textColor(severity: Double) = SMGState.stateTextColor(severity.round.toInt)

  def oidFilter(oid: String) = {
    val arr = SMGRemote.localId(oid).split("\\.")
    val optDot = if (arr.length > 1) "." else ""
    s"px=${arr.dropRight(1).mkString(".") + optDot}&sx=${optDot + arr.lastOption.getOrElse("")}"
  }
}


trait SMGMonState {

  val severity: Double
  def text: String
  val isHard: Boolean
  val isAcked: Boolean
  val isSilenced: Boolean
  val silencedUntil: Option[Int]
  val currentStateVal: SMGState.Value
  val oid: Option[String]
  val pfId: Option[String]
  val aggShowUrlFilter: Option[String]
  val remote : SMGRemote

  val errorRepeat: Int
  def badSince: Option[Int]

  def badSinceStr = badSince.map(bs => new Date(1000L * bs).toString)

  def alertKey: String

  private lazy val urlPx = "/dash?remote=" + remote.id + "&"

  private lazy val myShowUrlFilter: Option[String] = if (aggShowUrlFilter.isDefined) {
    aggShowUrlFilter
  } else if (oid.isDefined) {
    Some(SMGMonState.oidFilter(oid.get))
  } else None

  lazy val showUrl = if (myShowUrlFilter.isDefined) {
    urlPx + myShowUrlFilter.get
  } else "/monitor"

  lazy val isOk = currentStateVal == SMGState.OK
  def severityStr: String = SMGMonState.severityStr(severity)

  def severityColor = {
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

  def textColor = SMGMonState.textColor(severity)

  lazy val hardStr = if (isOk) "" else if (isHard) " HARD" else " SOFT"

  lazy val silencedUntilStr = if (silencedUntil.isEmpty) "permanently" else {
    "until " + new Date(silencedUntil.get.toLong * 1000).toString
  }

  lazy val isSilencedOrAcked = isSilenced || isAcked
  lazy val shouldNotify = isHard && (!isSilencedOrAcked)

  def notifySubject(smgHost: String, smgRemoteId: Option[String], isRepeat: Boolean) = {
    val repeatStr = if (isRepeat) "(repeat) " else ""
    s"${SMGMonNotifySeverity.fromStateValue(currentStateVal)}: ${smgRemoteId.map(rid => s"($rid) ").getOrElse("")}$repeatStr$alertKey"
  }

  private def bodyLink(smgBaseUrl: String, smgRemoteId: Option[String]) = if (myShowUrlFilter.isEmpty)
    s"${smgBaseUrl}/monitor#${smgRemoteId.getOrElse("")}"
  else
    s"${smgBaseUrl}/dash?remote=${smgRemoteId.getOrElse(SMGRemote.local.id)}&${myShowUrlFilter.get}"

  def notifyBody(smgBaseUrl: String, smgRemoteId: Option[String]) = {
      s"REMOTE: ${smgRemoteId.getOrElse("local")}\n\n" +
      s"MSG: ${text}\n\n" +
      s"LINK: ${bodyLink(smgBaseUrl, smgRemoteId)}\n\n" +
        (if (badSince.isDefined) s"BAD SINCE: ${badSinceStr.get}\n\n" else "")
  }

}

// "generic"/remote mon state
case class SMGMonStateView(severity: Double,
                           text: String,
                           isHard: Boolean,
                           isAcked: Boolean,
                           isSilenced: Boolean,
                           silencedUntil: Option[Int],
                           oid: Option[String],
                           pfId: Option[String],
                           aggShowUrlFilter: Option[String],
                           currentStateVal: SMGState.Value,
                           errorRepeat: Int,
                           badSince: Option[Int],
                           remote: SMGRemote
                          ) extends SMGMonState {

  def alertKey = oid.getOrElse(pfId.getOrElse(""))
}

// local agg (condensed) mon state
case class SMGMonStateAgg(lst: Seq[SMGMonState], showUrlFilter: String) extends SMGMonState {

  lazy val lstsz = lst.size

  override val severity =  if (lstsz == 0) 0 else lst.map(_.severity).max
  override def text = {
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
  override val oid = None
  override val pfId = None
  override val aggShowUrlFilter = Some(showUrlFilter)
  lazy val currentStateVal = SMGState(severity.toInt)

  override val errorRepeat = lst.map(_.errorRepeat).max
//  override lazy val hardStr = ""
  override val remote = SMGRemote.local

  // XXX chop off :ix portion of child alert keys to define this alert key.
  // Agg states cover entire objects in the context of alerting so var indexes are thrown away
  def alertKey = lst.map(_.alertKey.split(":")(0)).distinct.mkString(",")

  override val badSince: Option[Int] = {
    val badlst = lst.map(_.badSince).filter(_.isDefined).map(_.get)
    if (badlst.isEmpty)
      None
    else
      Some(badlst.min)
  }
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

  def buildMonState(lst: Seq[SMGMonStateObjVar]): SMGMonStateAgg = {
    SMGMonStateAgg(lst, objectsUrlFilter(lst.map(_.ouid)))
  }
}

case class SMGMonStatePreFetch(lst: Seq[SMGMonStateObjVar],
                               pfId: Option[String],
                               exitCode:Int,
                               errors: List[String],
                               errorRepeat: Int
                              ) extends SMGMonState {

  override val severity =  if (lst.isEmpty || exitCode == 0) 0 else lst.map(_.severity).max
  override def text = s"Fetch ${if (exitCode == 0) "recovery" else "error"}: " + lst.map(ms => ms.title).distinct.mkString(", ") + s": exit=$exitCode OUTPUT: " + errors.mkString("\n")
  override val isHard: Boolean = lst.exists(_.isHard)
  override val isAcked: Boolean = lst.forall(_.isAcked)
  override val isSilenced: Boolean = lst.forall(_.isSilenced)
  private val minSilencedUntil = lst.map(ovs => ovs.silencedUntil.getOrElse(Int.MaxValue)).min
  override val silencedUntil: Option[Int] = if (minSilencedUntil == Int.MaxValue) None else Some(minSilencedUntil)
  override val oid = None
  override val aggShowUrlFilter = Some(SMGMonStateAgg.objectsUrlFilter(lst.map(_.ouid)))
  lazy val currentStateVal = if (exitCode == 0) SMGState.OK else SMGState.E_FETCH
//  override lazy val hardStr = ""
  override val remote = SMGRemote.local
  def alertKey = pfId.get

  override val badSince: Option[Int] = {
    val badlst = lst.map(_.badSince).filter(_.isDefined).map(_.get)
    if (badlst.isEmpty)
      None
    else
      Some(badlst.min)
  }
}


// local object variable state
case class SMGMonStateObjVar(ouid: String,
                             ix: Int,
                             ovids: List[String],
                             pfId: Option[String],
                             title: String,
                             label: String,
                             isAcked: Boolean,
                             isSilenced: Boolean,
                             silencedUntil: Option[Int],
                             recentStates: List[SMGState],
                             badSince: Option[Int],
                             remote: SMGRemote
                         ) extends SMGMonState{

  //  def currentState = recentStates.head

  val desc = s"$title ($label)"

  def longDesc(s: SMGState) = s"$desc: ${s.desc} (ts=${s.timeStr})"

  lazy val severity = currentState.state.id.toDouble
  def text = longDesc(currentState)
  lazy val isHard = !recentStates.exists(_.state == SMGState.OK)
  lazy val currentState = recentStates.head

  val filterOids = (List(ouid) ++ ovids).distinct

  override val aggShowUrlFilter = if (filterOids.nonEmpty && filterOids.tail.nonEmpty) {
    Some(SMGMonStateAgg.objectsUrlFilter(filterOids))
  } else None

  override val oid = filterOids.headOption
  lazy val currentStateVal = currentState.state

  override val errorRepeat = {
    val lastOkIdx = recentStates.indexWhere(_.state == SMGState.OK)
    if (lastOkIdx == -1)
      recentStates.size
    else
      lastOkIdx
  }
  def alertKey = s"$ouid:$ix"
}

case class SMGMonStateGlobal(title: String,
                             label: String,
                             currentState: SMGState
                            ) extends SMGMonState{

  //  def currentState = recentStates.head

  val desc = s"$title ($label)"

  def longDesc(s: SMGState) = s"$desc: ${s.desc} (ts=${s.timeStr})"

  lazy val severity = currentState.state.id.toDouble
  def text = longDesc(currentState)
  lazy val isHard = true
  override val isAcked = false // TODO
  override val isSilenced = false // TODO
  override val silencedUntil = None
  override val aggShowUrlFilter = None
  override val oid = None
  override val pfId = None
  lazy val currentStateVal = currentState.state
  override val remote = SMGRemote.local

  val badSince: Option[Int] = None // TODO

  override val errorRepeat  = 1

  def alertKey = s"${SMGMonState.MON_STATE_GLOBAL_PX}$label"
}

case class SMGMonHeatmap(lst: List[SMGMonState], statesPerSquare: Int)

object SMGMonHeatmap {
  def join(lst: Seq[SMGMonHeatmap]): SMGMonHeatmap = {
    val newMsLst = lst.flatMap(_.lst)
    val newSps = if (lst.isEmpty) 1 else {
      lst.map(_.statesPerSquare).sum / lst.size
    }
    SMGMonHeatmap(newMsLst.toList, newSps)
  }
}

object SMGMonSilenceAction extends Enumeration {
  val ACK, SILENCE, ACK_PF, SILENCE_PF = Value
}

case class SMGMonSilenceAction(action: SMGMonSilenceAction.Value, silence: Boolean, until: Option[Int])

trait SMGMonitorApi {

  /**
    * Get all state objects for given sequence of object views
    * @param ovs - sequence of object views for which to get mon states
    * @return - async sequence of mon states
    */
  def objectViewStates(ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonStateObjVar]]]

  /**
    * get all local problematic SMGMonStates
    * @param includeSoft - whether to include sof errors or hard only
    * @param includeAcked - whether to include acknowledged problems
    * @return list of problenatic mon states
    */
  def localProblems(includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Seq[SMGMonState]

  /**
    * get all problematic SMGMonStates
    * @param includeSoft - whether to include soft errors or hard only
    * @param includeAcked - whether to include acknowledged problems
    * @return list of tuples (remote, list of problem mon states) one for each remote
    */
  def problems(includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Future[Seq[(SMGRemote, Seq[SMGMonState])]]

  /**
    * Get the combined state for all rrd objects sharing the same parent pre_fetch
    * @param cmdId
    * @return
    */
  def fetchCommandState(cmdId: String): Future[Option[SMGMonState]]


  /**
    * silence/unsilence an object problem
    * @param ouid - object (update) id
    * @param action - see  SMGMonSilenceAction
    */
  def silenceObject(ouid:String, action: SMGMonSilenceAction): Future[Boolean]


  def silenceFetchCommand(cmdId: String, until: Option[Int]): Future[Boolean]

  /**
    * Generate a heatmap from local for the system objects. A heatmap is (possibly condensed) list of SMGMonState squares.
    * @param flt - filter to use to get objects
    * @param maxSize - limit the heatmap to that many squares (note max width is enforced separately).
    * @param offset - offset in the filtered objects list to start the heatmap from
    * @param limit - limit the number of filtered objects to include
    * @return
    */
  def localHeatmap(flt: SMGFilter, maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): SMGMonHeatmap

  /**
    * Generate a sequence of heatmaps (by remote). A heatmap is (possibly condensed) list of SMGMonState squares.
    * @param flt - filter to use to get objects
    * @param maxSize - limit the heatmap to that many squares (note max width is enforced separately).
    * @param offset - offset in the filtered objects list to start the heatmap from
    * @param limit - limit the number of filtered objects to include
    * @return  - sequence of (remote,heatmap) tuples
    */
  def heatmap(flt: SMGFilter, maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[Seq[(SMGRemote, SMGMonHeatmap)]]

  def saveStateToDisk(): Unit

  /**
    * a convenience reference to the SMGMonitorLogApi
    */
  val monLogApi: SMGMonitorLogApi


  def inspect(ov:SMGObjectView): Option[String]

}
