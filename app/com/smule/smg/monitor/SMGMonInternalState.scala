package com.smule.smg.monitor

import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGIndex, SMGLogger, SMGLoggerApi}
import com.smule.smg.remote.SMGRemote

/**
  * Created by asen on 11/12/16.
  */

/**
  * The internal (mutable) representation of a SMG state which
  * keeps all relevant state data and takes care of common processing
  * tasks. The specific subclasses implement any differences based
  * their type (run state, pre-fetch state, object state and var state)
  */
trait SMGMonInternalState extends SMGMonState {
  //val id: String - from SMGTreeNode
  //val parentId: String - from SMGTreeNode

  override def recentStates: Seq[SMGState] = myRecentStates
  override def isHard: Boolean = myIsHard
  override def isAcked: Boolean = myIsAcked
  override def isSilenced: Boolean = silencedUntil.isDefined
  def isInherited: Boolean = myStateIsInherited

  // XXX flag to distinguish newly created states, this is set to false in
  // SMGMonitor.silenceNewNotSilencedChildren (where it is checked) and also in deserialize
  var justCreated: Boolean = true

  override def silencedUntil: Option[Int] = {
    val sopt = myIsSilencedUntil // copy to avoid race condition in the if clause
    if (sopt.isDefined && sopt.get < SMGState.tssNow) {
      myIsSilencedUntil = None
    }
    myIsSilencedUntil
  }

  override def severity: Double = currentStateVal.id.toDouble
  override def remote: SMGRemote = SMGRemote.local

  def pluginId: Option[String]

  def errorRepeat: Int = {
    val mylst = myRecentStates.take(maxHardErrorCount)
    if (mylst.head.isOk)
      return 0
    val (nonOk, rest) = mylst.span(!_.isOk)
    nonOk.size
  }

  def badSince: Option[Int] = if (currentState.isOk) None else Some(myLastOkStateChange)

  // abstracts
  def ouids: Seq[String]
  protected def vixOpt: Option[Int]
  protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int)
  protected def getMaxHardErrorCount: Int

  protected val configSvc: SMGConfigService
  protected val monLog: SMGMonitorLogApi
  protected val notifSvc: SMGMonNotifyApi

  def currentState: SMGState = myRecentStates.head

  protected var myRecentStates: List[SMGState] = List(SMGState.initialState)
  protected var myLastOkStateChange = 0
  protected var myLastGoodBadStateDuration = 0
  protected var myLastStateChange = 0

  protected var myIsHard: Boolean = true
  protected var myIsAcked: Boolean = false
  protected var myIsSilencedUntil: Option[Int] = None
  protected var myStateIsInherited = false

  // XXX these are applicable to var state only but are put here to simplify serisalization/deserialization
  protected var myCounterPrevValue: Double = Double.NaN
  protected var myCounterPrevTs = 0

  private var myMaxHardErrorCount: Option[Int] = None

  protected def maxHardErrorCount: Int = {
    if (myMaxHardErrorCount.isEmpty)
      myMaxHardErrorCount = Some(getMaxHardErrorCount)
    myMaxHardErrorCount.get
  }

  def configReloaded(): Unit = {
    myMaxHardErrorCount = Some(getMaxHardErrorCount)
  }

  def maxRecentStates: Int = maxHardErrorCount + 1

  protected val log: SMGLoggerApi = SMGLogger

  private def distincRefObjs: Seq[String] = ouids.flatMap { ouid =>
    configSvc.config.viewObjectsById.get(ouid).flatMap(_.refObj.map(_.id))
  }.distinct

  override def aggShowUrlFilter: Option[String] = {
    if (ouids.isEmpty)
      return None
    if (ouids.tail.isEmpty)
      return Some(SMGMonState.oidFilter(ouids.head))
    // also handle the case where the ouids are aliases to the same update object,
    // in that case we don't want a prx= filter
    if (pfId.isDefined && distincRefObjs.lengthCompare(1) > 0)
      return Some("prx=" + java.net.URLEncoder.encode(s"^${SMGRemote.localId(pfId.get)}$$", "UTF-8"))
    val rx = s"^(${ouids.map(oid => SMGRemote.localId(oid)).distinct.mkString("|")})$$"
    Some("rx=" + java.net.URLEncoder.encode(rx, "UTF-8")) // TODO, better showUrl?
  }

  protected def currentStateDesc: String = {
    val goodBadSince = if (myLastStateChange == 0 || myLastOkStateChange == 0) ""
    else if (currentState.isOk) {
      val badDuration = if (myLastGoodBadStateDuration > 0) s", was bad for ${SMGState.formatDuration(myLastGoodBadStateDuration)}" else ""
      s", good since ${SMGState.formatTss(myLastOkStateChange)}$badDuration"
    } else {
      val goodDuration = if (myLastGoodBadStateDuration > 0) s", was good for ${SMGState.formatDuration(myLastGoodBadStateDuration)}" else ""
      val lastChange = if (myLastStateChange != myLastOkStateChange) s", last change ${SMGState.formatTss(myLastStateChange)}" else ""
      s", rpt=$errorRepeat/$maxHardErrorCount, bad since ${SMGState.formatTss(myLastOkStateChange)}$lastChange$goodDuration"
    }
    s"${currentState.desc} (ts=${currentState.timeStr}$goodBadSince)"
  }

  protected def logEntry(logIsHard: Boolean): SMGMonitorLogMsg =
    SMGMonitorLogMsg(currentState.ts, Some(this.id), currentState, myRecentStates.tail.headOption,
      errorRepeat, isHard = logIsHard, isAcked = isAcked, isSilenced = isSilenced , ouids , vixOpt, remote)

  protected def processAlertsAndLogs(prevStateWasInherited: Boolean): Unit = {
    val curState: SMGState = myRecentStates.head
    val prevStates = myRecentStates.tail

    if (curState.isOk && prevStates.head.isOk)
      return // no changes to report

    lazy val wasHardError = prevStates.take(maxHardErrorCount).forall(!_.isOk)
    lazy val isHardError = !curState.isOk && prevStates.take(maxHardErrorCount - 1).forall(!_.isOk)

    if (curState.isOk) { // recovery
      notifSvc.sendRecoveryMessages(this)
      if (!prevStateWasInherited)
        monLog.logMsg(logEntry(wasHardError))
    } else { // error state
      val isStateChange = curState.state != prevStates.head.state
      val isImprovement = curState.state < prevStates.head.state
      val isHardChanged = isHardError && !wasHardError
      lazy val (notifCmds, backoff) = notifyCmdsAndBackoff
      if (isStateChange || isHardChanged || prevStateWasInherited) {
        if (isHardError) {
          if (!isSilencedOrAcked)
            notifSvc.sendAlertMessages(this, notifCmds, isImprovement)
        }
        monLog.logMsg(logEntry(isHardError))
      } else {
        //no log msg - continuous hard error - just resend notifications if applicable
        if (!isSilencedOrAcked)
          notifSvc.checkAndResendAlertMessages(this, backoff)
      }
    }
  }

  def addState(state: SMGState,  isInherited: Boolean): Unit = {
    if (myRecentStates.isEmpty) {
      log.warn(s"SMGMonInternalState.addState: empty myRecentStates for $id")
      myRecentStates = List(state)
      return
    }

    //check if we are flipping OK/non-OK state and update myLastOkStateChange
    //also record how long this state was in trouble
    if (state.isOk != myRecentStates.head.isOk) {
      if (myLastOkStateChange != 0)
        myLastGoodBadStateDuration = state.ts - myLastOkStateChange
      myLastOkStateChange = state.ts
    }

    // update myLastStateChange if any state value change
    if (state.state != myRecentStates.head.state) {
      myLastStateChange = state.ts
    }

    // prepend our new state
    myRecentStates = state :: myRecentStates

    myIsHard = state.isOk || myRecentStates.take(maxHardErrorCount).forall(!_.isOk)

    if (!isInherited)
      processAlertsAndLogs(myStateIsInherited)  // before myStateIsInherited has ben updated and recentStates have been dropped

    // remember if current state was eligible to trigger alert/logs
    myStateIsInherited = isInherited

    // drop obsolete state(s)
    myRecentStates = myRecentStates.take(maxRecentStates)

    // remove any acknowledgements if states is OK
    if (state.state == SMGState.OK)
      myIsAcked = false
  }

  def ack(): Unit = myIsAcked = true
  def unack(): Unit = myIsAcked = false
  def slnc(until: Int): Unit = myIsSilencedUntil = Some(until)
  def unslnc(): Unit = myIsSilencedUntil = None

  def serialize: JsValue = {
    val mm = mutable.Map[String,JsValue](
      "sts" -> Json.toJson(SMGState.tssNow),
      "sid" -> Json.toJson(this.id),
      "rss" -> Json.toJson(myRecentStates.map { ss =>
        Json.toJson(Map("t" -> Json.toJson(ss.ts), "s" -> Json.toJson(ss.state.toString), "d" -> Json.toJson(ss.desc)))
      }),
      "lsc" -> Json.toJson(myLastStateChange),
      "lkc" -> Json.toJson(myLastOkStateChange),
      "lgbd" -> Json.toJson(myLastGoodBadStateDuration),
      "cpt" -> Json.toJson(myCounterPrevTs)
    )
    if (!myCounterPrevValue.isNaN) mm += ("cpv" -> Json.toJson(myCounterPrevValue))
    if (myIsHard) mm += ("hrd" -> Json.toJson("true"))
    if (myIsAcked) mm += ("ack" -> Json.toJson("true"))
    if (myIsSilencedUntil.isDefined) mm += ("slc" -> Json.toJson(myIsSilencedUntil.get))
    if (myStateIsInherited) mm += ("sih" -> Json.toJson("true"))
    Json.toJson(mm.toMap)
  }

  def deserialize(src: JsValue): Unit = {
    try {
      justCreated = false

      val sts = (src \ "sts").get.as[Int]
      //val statsAge = SMGState.tssNow - sts
      val sid = (src \ "sid").get.as[String]

      // sanity check, should never happen
      if (sid != id){
        log.error(s"SMGMonInternalState.deserialize: sid != id ($sid != $id)")
        return
      }

      myRecentStates = (src \ "rss").as[List[JsValue]].map { jsv =>
          SMGState((jsv \ "t").as[Int],
            SMGState.fromName((jsv \ "s").as[String]),
            (jsv \ "d").as[String])
        }

      myLastStateChange = (src \ "lsc").as[Int]
      myLastOkStateChange = (src \ "lkc").as[Int]
      myLastGoodBadStateDuration = (src \ "lgbd").asOpt[Int].getOrElse(0) // TODO reading Option for backwards compatibility
      myCounterPrevTs = (src \ "cpt").as[Int]

      myCounterPrevValue = (src \ "cpv").asOpt[Double].getOrElse(Double.NaN)
      myIsHard = (src \ "hrd").asOpt[String].contains("true")
      myIsAcked = (src \ "ack").asOpt[String].contains("true")
      myStateIsInherited = (src \ "sih").asOpt[String].contains("true")
      myIsSilencedUntil = (src \ "slc").asOpt[Int]
    } catch {
      case x: Throwable => log.ex(x, s"SMGMonInternalState.deserialize: Unexpected exception for $id: src=$src")
    }
  }

  def inspect: String = serialize.toString

  override def getLocalMatchingIndexes: Seq[SMGIndex] = {
    val ovs = ouids.flatMap { ouid => configSvc.config.viewObjectsByUpdateId.getOrElse(ouid, Seq()) }
    val allIxes = configSvc.config.indexes
    val ret = mutable.Set[SMGIndex]()
    ovs.foreach { ov =>
      allIxes.foreach { ix =>
        val matches = (!ix.flt.matchesAnyObjectIdAndText) && ix.flt.matches(ov)
        if (matches)
          ret.add(ix)
      }
    }
    ret.toSeq.sortBy(_.title)
  }
}
