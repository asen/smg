package com.smule.smg

import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

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

  override def aggShowUrlFilter: Option[String] = {
    if (ouids.isEmpty)
      return None
    if (ouids.tail.isEmpty)
      return Some(SMGMonState.oidFilter(ouids.head))
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

  protected def logEntry(logIsHard: Boolean) = SMGMonitorLogMsg(currentState.ts, Some(this.id), currentState, myRecentStates.tail.headOption,
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

  override def getLocalMatchingIndexes: Seq[SMGIndex] = {
    val ovs = ouids.map { ouid => configSvc.config.viewObjectsById.get(ouid) }.filter(_.isDefined).map(_.get)
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

class SMGMonVarState(var objectUpdate: SMGObjectUpdate,
                     vix: Int,
                     val configSvc: SMGConfigService,
                     val monLog: SMGMonitorLogApi,
                     val notifSvc: SMGMonNotifyApi) extends SMGMonInternalState {

  override def alertKey: String = id

  override val id: String = SMGMonVarState.stateId(objectUpdate, vix)
  override val parentId: Option[String] = Some(objectUpdate.id)

  override def pluginId: Option[String] = objectUpdate.pluginId

  override def ouids: Seq[String] = (Seq(objectUpdate.id) ++
    configSvc.config.viewObjectsByUpdateId.getOrElse(objectUpdate.id, Seq()).map(_.id)).distinct
  override def vixOpt: Option[Int] = Some(vix)
  override def oid: Option[String] = Some(objectUpdate.id)
  override def pfId: Option[String] = objectUpdate.preFetch

  private def label =  objectUpdate.vars(vix).getOrElse("label", "ds" + vix)

  override def alertSubject: String = s"${objectUpdate.id}[$vix]:$label"

  override def text: String = s"${objectUpdate.id}[$vix]:$label: ${objectUpdate.title}: $currentStateDesc"

  private def processCounterUpdate(ts: Int, rawVal: Double): Option[(Double,Option[String])] = {
    val tsDelta = ts - myCounterPrevTs

    val ret = if (tsDelta > (objectUpdate.interval * 3)) {
      if (myCounterPrevTs > 0) {
        log.debug(s"SMGMonVarState.processCounterUpdate($id): Time delta is too big: $ts - $myCounterPrevTs = $tsDelta")
      }
      None
    } else if (tsDelta <= 0) {
      log.error(s"SMGMonVarState.processCounterUpdate($id): Non-positive time delta detected: $ts - $myCounterPrevTs = $tsDelta")
      None
    } else {
      val maxr = objectUpdate.vars(vix).get("max").map(_.toDouble)
      val r = (rawVal - myCounterPrevValue) / tsDelta
      val tpl = if ((r < 0) || (maxr.isDefined && (maxr.get < r))){
        log.debug(s"SMGMonVarState.processCounterUpdate($id): Counter overflow detected: " +
          s"p=$myCounterPrevValue/$myCounterPrevTs c=$rawVal/$ts r=$r maxr=$maxr")
        (Double.NaN, Some(s"ANOM: Counter overflow: p=${objectUpdate.numFmt(myCounterPrevValue, vix)} " +
          s"c=${objectUpdate.numFmt(rawVal, vix)} td=$tsDelta r=${objectUpdate.numFmt(r, vix)} " +
          s"maxr=${objectUpdate.numFmt(maxr.getOrElse(Double.NaN), vix)}"))
      } else
        (r, None)
      Some(tpl)
    }
    myCounterPrevValue = rawVal
    myCounterPrevTs = ts
    ret
  }

  private def myNumFmt(d: Double): String = objectUpdate.numFmt(d, vix)

  def processValue(ts: Int, rawVal: Double): Unit = {
    val valOpt = if (objectUpdate.isCounter) {
      processCounterUpdate(ts, rawVal)
    } else Some((rawVal, None))
    if (valOpt.isEmpty) {
      return
    }
    val (newVal, nanDesc) = valOpt.get
    val alertConfs = configSvc.objectValueAlertConfs(objectUpdate, vix)
    val ret: SMGState = if (newVal.isNaN) {
      SMGState(ts, SMGState.ANOMALY, s"ANOM: value=NaN (${nanDesc.getOrElse("unknown")})")
    } else if (alertConfs.nonEmpty) {
      val allCheckStates = alertConfs.map { alertConf =>
        alertConf.checkValue(objectUpdate, vix, ts, newVal)
      }
      val errRet = allCheckStates.maxBy(_.state)
      if (alertConfs.nonEmpty && (errRet.state == SMGState.OK)) {
        val descSx = alertConfs.map { _.threshDesc(myNumFmt) }.mkString(", ")
        SMGState(ts,SMGState.OK, s"OK: value=${myNumFmt(newVal)} : $descSx")
      } else
        errRet
    } else {
      SMGState(ts, SMGState.OK, s"OK: value=${myNumFmt(newVal)}")
    }
    if (ret.state != SMGState.OK) {
      log.debug(s"MONITOR: ${objectUpdate.id} : $vix : $ret")
    }
    addState(ret, isInherited = false)
  }

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    lazy val mntype = SMGMonNotifySeverity.fromStateValue(currentStateVal)
    configSvc.objectVarNotifyCmdsAndBackoff(objectUpdate, Some(vix), mntype)
  }

  override protected def getMaxHardErrorCount: Int = {
    configSvc.objectVarNotifyStrikes(objectUpdate, Some(vix))
  }

}

object SMGMonVarState {
  def stateId(ou: SMGObjectUpdate, vix: Int) = s"${ou.id}:$vix"
}

trait SMGMonBaseFetchState extends SMGMonInternalState {

  def processError(ts: Int, exitCode :Int, errors: List[String], isInherited: Boolean): Unit = {
    val errorMsg = s"Fetch error: exit=$exitCode, OUTPUT: " + errors.mkString("\n")
    addState(SMGState(ts, SMGState.UNKNOWN, errorMsg), isInherited)
  }

  def processSuccess(ts: Int, isInherited: Boolean): Unit = {
    addState(SMGState(ts, SMGState.OK, "OK"), isInherited)
  }

  protected def pfNotifyCmdsAndBackoff(myConfigSvc: SMGConfigService,
                                       myNotifyConf: Option[SMGMonNotifyConf],
                                       ous: Seq[SMGObjectUpdate]
                                      ): (Seq[SMGMonNotifyCmd], Int) = {
    if (myNotifyConf.isDefined) {
      val ncmds = if (myNotifyConf.get.notifyDisable)
        Seq()
      else
        myNotifyConf.get.unkn.map(s => myConfigSvc.config.notifyCommands.get(s)).filter(_.isDefined).map(_.get)
      val backoff = myNotifyConf.get.notifyBackoff.getOrElse(myConfigSvc.config.globalNotifyBackoff)
      (ncmds, backoff)
    } else {
      val tuples = ous.
        map(ou => myConfigSvc.objectVarNotifyCmdsAndBackoff(ou, None, SMGMonNotifySeverity.UNKNOWN))
      val backoff = tuples.map(_._2).max
      val ncmds = tuples.flatMap(_._1).distinct
      (ncmds, backoff)
    }
  }

  protected def pfMaxHardErrorCount(myConfigSvc: SMGConfigService,
                                     myNotifyConf: Option[SMGMonNotifyConf],
                                     ous: Seq[SMGObjectUpdate]): Int = {
    if (myNotifyConf.isDefined) {
      myNotifyConf.get.notifyStrikes.getOrElse(myConfigSvc.config.globalNotifyStrikes)
    } else {
      if (ous.isEmpty) {
        myConfigSvc.config.globalNotifyStrikes
      } else {
        ous.map { ou => myConfigSvc.objectVarNotifyStrikes(ou, None) }.min
      }
    }
  }
}

class SMGMonObjState(var objectUpdate: SMGObjectUpdate,
                     val configSvc: SMGConfigService,
                     val monLog: SMGMonitorLogApi,
                     val notifSvc: SMGMonNotifyApi) extends SMGMonBaseFetchState {
  override val id: String = SMGMonObjState.stateId(objectUpdate)

  override def alertKey: String = id

  override def parentId: Option[String] = objectUpdate.preFetch.map(SMGMonPfState.stateId)

  override def pluginId: Option[String] = objectUpdate.pluginId

  override def ouids: Seq[String] = (Seq(objectUpdate.id) ++
    configSvc.config.viewObjectsByUpdateId.getOrElse(objectUpdate.id, Seq()).map(_.id)).distinct
  override def vixOpt: Option[Int] = None

  override def oid: Option[String] = Some(objectUpdate.id)
  override def pfId: Option[String] = objectUpdate.preFetch
  override def text: String = s"${objectUpdate.id}(intvl=${objectUpdate.interval}): ${objectUpdate.title}: $currentStateDesc"

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    pfNotifyCmdsAndBackoff(configSvc, objectUpdate.notifyConf, Seq(objectUpdate))
  }

  override protected def getMaxHardErrorCount: Int = {
    pfMaxHardErrorCount(configSvc, objectUpdate.notifyConf, Seq(objectUpdate))
  }

}

object SMGMonObjState {
  def stateId(ou: SMGObjectUpdate) = s"${ou.id}"
}

class SMGMonPfState(var pfCmd: SMGPreFetchCmd,
                    intervals: Seq[Int],
                    val pluginId: Option[String],
                    val configSvc: SMGConfigService,
                    val monLog: SMGMonitorLogApi,
                    val notifSvc: SMGMonNotifyApi)  extends SMGMonBaseFetchState {
  override val id: String = pfCmd.id //SMGMonPfState.stateId(pfCmd, interval)
  override def parentId: Option[String] = pfCmd.preFetch // SMGMonPfState.fetchParentStateId(pfCmd.preFetch, intervals.min, pluginId)

  private def myObjectUpdates = if (pluginId.isEmpty) {
    configSvc.config.getFetchCommandRrdObjects(pfCmd.id, intervals)
  } else {
    configSvc.config.getPluginFetchCommandUpdateObjects(pluginId.get, pfCmd.id)
  }

  override def ouids: Seq[String] = myObjectUpdates.map(_.id)
  override def vixOpt: Option[Int] = None
  override def oid: Option[String] = None
  override def pfId: Option[String] = Some(pfCmd.id)

  override def text: String = s"${pfCmd.id}(intvls=${intervals.mkString(",")}): $currentStateDesc"

  override def alertSubject: String = s"${pfCmd.id}[intvls=${intervals.mkString(",")}]"

  override def alertKey: String = pfCmd.id

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    pfNotifyCmdsAndBackoff(configSvc, pfCmd.notifyConf, myObjectUpdates)
  }

  override def getMaxHardErrorCount: Int = {
    pfMaxHardErrorCount(configSvc, pfCmd.notifyConf, myObjectUpdates)
  }
}

object SMGMonPfState {
  def stateId(pfCmd: SMGPreFetchCmd): String = stateId(pfCmd.id)
  def stateId(pfCmdId: String): String = pfCmdId
}

class SMGMonRunState(val interval: Int,
                     val pluginId: Option[String],
                     val configSvc: SMGConfigService,
                     val monLog: SMGMonitorLogApi,
                     val notifSvc: SMGMonNotifyApi) extends SMGMonInternalState {

  override val id: String = SMGMonRunState.stateId(interval, pluginId)

  override def alertKey: String = id

  override def parentId: Option[String] = None

  override def ouids: Seq[String] = Seq()
  override def vixOpt: Option[Int] = None

  override def oid: Option[String] = None
  override def pfId: Option[String] = None

  override def text: String = currentState.desc

  private val pluginDesc = pluginId.map(s => s" (plugin - $s)").getOrElse("")

  def processOk(ts:Int): Unit = addState(SMGState(ts, SMGState.OK, s"interval $interval$pluginDesc - OK"), isInherited = false)

  def processOverlap(ts: Int): Unit = addState(
    SMGState(ts, SMGState.SMGERR, s"interval $interval$pluginDesc - overlapping runs"),
    isInherited = false)

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    val ncmds = configSvc.globalNotifyCmds(SMGMonNotifySeverity.SMGERR)
    (ncmds, 0)
  }

  override protected def getMaxHardErrorCount = 2 // TODO read from config???
}

object SMGMonRunState {
  def stateId(interval: Int, pluginId: Option[String]): String = "$interval_%04d".format(interval) +
    pluginId.map(s => s"-$s").getOrElse("")
}
