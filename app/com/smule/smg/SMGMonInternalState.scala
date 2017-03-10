package com.smule.smg

import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable

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

  override def silencedUntil: Option[Int] = {
    val sopt = myIsSilencedUntil // copy to avoid race condition in the if clause
    if (sopt.isDefined && sopt.get < SMGState.tssNow) {
      myIsSilencedUntil = None
    }
    myIsSilencedUntil
  }

  override def severity: Double = currentStateVal.id.toDouble
  override def remote: SMGRemote = SMGRemote.local

  override def alertKey: String = id

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

  //protected val configSvc: SMGConfigService
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
  protected var myMovingStatsOpt: Option[SMGMonValueMovingStats] = None
  protected var myCounterPrevValue = Double.NaN
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

  protected def maxRecentStates: Int = maxHardErrorCount

  protected val log = SMGLogger

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
    while (myRecentStates.size > maxRecentStates) {
      myRecentStates = myRecentStates.dropRight(1)
    }

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
    if (myMovingStatsOpt.isDefined) mm += ("mvs" -> myMovingStatsOpt.get.serialize)
    Json.toJson(mm.toMap)
  }

  def deserialize(src: JsValue): Unit = {
    try {
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
            SMGState.withName((jsv \ "s").as[String]),
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

      val mvsOpt = (src \ "mvs").asOpt[JsValue]

      if (mvsOpt.isDefined && myMovingStatsOpt.isDefined) {
        SMGMonValueMovingStats.deserialize(mvsOpt.get, myMovingStatsOpt.get)
      } else if (mvsOpt.isDefined != myMovingStatsOpt.isDefined) {
        log.error(s"SMGMonInternalState.deserialize: unexpected moving stats state: " +
          s"js -> ${mvsOpt.isDefined}, mine -> ${myMovingStatsOpt.isDefined}")
      }
    } catch {
      case x: Throwable => log.ex(x, s"SMGMonInternalState.deserialize: Unexpected exception for $id: src=$src")
    }
  }
}

class SMGMonVarState(var ou: SMGObjectUpdate,
                     vix: Int,
                     val configSvc: SMGConfigService,
                     val monLog: SMGMonitorLogApi,
                     val notifSvc: SMGMonNotifyApi) extends SMGMonInternalState {

  override val id: String = SMGMonVarState.stateId(ou,vix)
  override val parentId: Option[String] = Some(ou.id)

  override def ouids: Seq[String] = (Seq(ou.id) ++ configSvc.config.viewObjectsByUpdateId.getOrElse(ou.id, Seq()).map(_.id)).distinct
  override def vixOpt: Option[Int] = Some(vix)
  override def oid: Option[String] = Some(ou.id)
  override def pfId: Option[String] = ou.preFetch

  private def label =  ou.vars(vix).getOrElse("label", "ds" + vix)

  override def alertSubject: String = s"${ou.id}[$vix]:$label"

  override def text: String = s"${ou.id}($vix:$label): ${ou.title}: $currentStateDesc"

  myMovingStatsOpt = Some(new SMGMonValueMovingStats(ou.id, vix, ou.interval))
//  myCounterPrevValue = Double.NaN
//  myCounterPrevTs = 0

  private def movingStats = myMovingStatsOpt.get

  private def numFmt(num: Double): String = {
    val myNum = ou.vars(vix).get("cdef").map(cdf => SMGRrd.computeCdef(cdf, num)).getOrElse(num)
    SMGState.numFmt(myNum) + ou.vars(vix).get("mu").map(mu => s" $mu").getOrElse("")
  }

  private def processCounterUpdate(ts: Int, rawVal: Double): Option[(Double,Option[String])] = {
    val tsDelta = ts - myCounterPrevTs

    val ret = if (tsDelta > (ou.interval * 3)) {
      if (myCounterPrevTs > 0) {
        log.debug(s"SMGMonVarState.processCounterUpdate($id): Time delta is too big: $ts - $myCounterPrevTs = $tsDelta")
      }
      None
    } else if (tsDelta <= 0) {
      log.error(s"SMGMonVarState.processCounterUpdate($id): Non-positive time delta detected: $ts - $myCounterPrevTs = $tsDelta")
      None
    } else {
      val maxr = ou.vars(vix).get("max").map(_.toDouble)
      val r = (rawVal - myCounterPrevValue) / tsDelta
      val tpl = if ((r < 0) || (maxr.isDefined && (maxr.get < r))){
        log.debug(s"SMGMonVarState.processCounterUpdate($id): Counter overflow detected: p=$myCounterPrevValue/$myCounterPrevTs c=$rawVal/$ts r=$r maxr=$maxr")
        (Double.NaN, Some(s"Counter overflow: p=${numFmt(myCounterPrevValue)} c=${numFmt(rawVal)} td=$tsDelta r=${numFmt(r)} maxr=${numFmt(maxr.getOrElse(Double.NaN))}"))
      } else
        (r, None)
      Some(tpl)
    }
    myCounterPrevValue = rawVal
    myCounterPrevTs = ts
    ret
  }

  def processValue(ts: Int, rawVal: Double): Unit = {
    val valOpt = if (ou.isCounter) {
      processCounterUpdate(ts, rawVal)
    } else Some((rawVal, None))
    if (valOpt.isEmpty) {
      return
    }
    val (newVal, nanDesc) = valOpt.get
    val alertConfs = configSvc.objectValueAlertConfs(ou, vix)
    var ret : SMGState = null
    if (newVal.isNaN) {
      ret = SMGState(ts, SMGState.E_ANOMALY, s"ANOM: value=NaN (${nanDesc.getOrElse("unknown")})")
    } else if (alertConfs.nonEmpty) {
      if (!alertConfs.exists(_.spike.isDefined)) movingStats.reset()
      var movingStatsUpdated = false
      val allAlertStates = alertConfs.map { alertConf =>
        var curRet: SMGState = null
        val warnThreshVal = alertConf.warn.map(_.value).getOrElse(Double.NaN)
        val critThreshVal = alertConf.crit.map(_.value).getOrElse(Double.NaN)
        val descSx = s"( ${numFmt(warnThreshVal)} / ${numFmt(critThreshVal)} )"

        if (alertConf.crit.isDefined) {
          val alertDesc = alertConf.crit.get.checkAlert(newVal, numFmt)
          if (alertDesc.isDefined)
            curRet = SMGState(ts, SMGState.E_VAL_CRIT, s"CRIT: ${alertDesc.get} : $descSx")
        }
        if ((curRet == null) && alertConf.warn.isDefined ) {
          val alertDesc = alertConf.warn.get.checkAlert(newVal, numFmt)
          if (alertDesc.isDefined)
            curRet = SMGState(ts,SMGState.E_VAL_WARN, s"WARN: ${alertDesc.get} : $descSx")
        }
        if (alertConf.spike.isDefined) {
          // Update short/long term averages, to be used for spike/drop detection
          val ltMaxCounts = alertConf.spike.get.maxLtCnt(ou.interval)
          val stMaxCounts = alertConf.spike.get.maxStCnt(ou.interval)
          if (!movingStatsUpdated) {
            movingStats.update(ts, newVal, stMaxCounts, ltMaxCounts)
            movingStatsUpdated = true
          }
          // check for spikes
          if (curRet == null) {
            val alertDesc = alertConf.spike.get.checkAlert(movingStats, stMaxCounts, ltMaxCounts, numFmt)
            if (alertDesc.isDefined)
              curRet = SMGState(ts, SMGState.E_ANOMALY, s"ANOM: ${alertDesc.get}")
          }
        }
        if (curRet == null) {
          curRet = SMGState(ts,SMGState.OK, s"OK: value=${numFmt(newVal)} : $descSx")
        }
        curRet
      }
      ret = allAlertStates.maxBy(_.state)
    } else movingStats.reset()
    if (ret == null) {
      ret = SMGState(ts, SMGState.OK, s"OK: value=${numFmt(newVal)}")
    }
    if (ret.state != SMGState.OK) {
      log.debug(s"MONITOR: ${ou.id} : $vix : $ret")
    }
    addState(ret, isInherited = false)
  }

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    lazy val mntype = SMGMonNotifySeverity.fromStateValue(currentStateVal)
    configSvc.objectVarNotifyCmdsAndBackoff(ou, Some(vix), mntype)
  }

  override protected def getMaxHardErrorCount: Int = {
    configSvc.objectVarNotifyStrikes(ou, Some(vix))
  }

}

object SMGMonVarState {
  def stateId(ou: SMGObjectUpdate, vix: Int) = s"${ou.id}:$vix"
}

trait SMGMonBaseFetchState extends SMGMonInternalState {

  def processError(ts: Int, exitCode :Int, errors: List[String], isInherited: Boolean): Unit = {
    val errorMsg = s"Fetch error: exit=$exitCode, OUTPUT: " + errors.mkString("\n")
    addState(SMGState(ts, SMGState.E_FETCH, errorMsg), isInherited)
  }

  def processSuccess(ts: Int, isInherited: Boolean): Unit = {
    addState(SMGState(ts, SMGState.OK, "OK"), isInherited)
  }
}

class SMGMonObjState(var ou: SMGObjectUpdate,
                     val configSvc: SMGConfigService,
                     val monLog: SMGMonitorLogApi,
                     val notifSvc: SMGMonNotifyApi) extends SMGMonBaseFetchState {
  override val id: String = SMGMonObjState.stateId(ou)

  override def parentId: Option[String] = SMGMonPfState.fetchParentStateId(ou.preFetch, ou.interval, ou.pluginId)

  override def ouids: Seq[String] = (Seq(ou.id) ++ configSvc.config.viewObjectsByUpdateId.getOrElse(ou.id, Seq()).map(_.id)).distinct
  override def vixOpt: Option[Int] = None

  override def oid: Option[String] = Some(ou.id)
  override def pfId: Option[String] = ou.preFetch
  override def text: String = s"${ou.id}: ${ou.title}: $currentStateDesc"

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    lazy val mntype = SMGMonNotifySeverity.fromStateValue(currentStateVal)
    configSvc.objectVarNotifyCmdsAndBackoff(ou, None, mntype)
  }

  override protected def getMaxHardErrorCount: Int = {
    configSvc.objectVarNotifyStrikes(ou, None)
  }

}

object SMGMonObjState {
  def stateId(ou: SMGObjectUpdate) = s"${ou.id}"
}

class SMGMonPfState(var pfCmd: SMGPreFetchCmd,
                    interval:Int,
                    val pluginId: Option[String],
                    val configSvc: SMGConfigService,
                    val monLog: SMGMonitorLogApi,
                    val notifSvc: SMGMonNotifyApi)  extends SMGMonBaseFetchState {
  override val id: String = SMGMonPfState.stateId(pfCmd, interval)
  override def parentId: Option[String] = SMGMonPfState.fetchParentStateId(pfCmd.preFetch, interval, pluginId)

  override def ouids: Seq[String] = configSvc.config.fetchCommandRrdObjects(pfCmd.id, Some(interval)).map(_.id)
  override def vixOpt: Option[Int] = None
  override def oid: Option[String] = None
  override def pfId: Option[String] = Some(pfCmd.id)

  override def text: String = s"${pfCmd.id}(intvl=$interval): $currentStateDesc"

  override def alertSubject: String = s"${pfCmd.id}[intvl=$interval]"

  override def alertKey: String = pfCmd.id

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    val tuples = configSvc.config.fetchCommandRrdObjects(pfCmd.id, Some(interval)).
      map(ou => configSvc.objectVarNotifyCmdsAndBackoff(ou,None, SMGMonNotifySeverity.UNKNOWN))
    lazy val backoff = tuples.map(_._2).max
    lazy val ncmds = tuples.flatMap(_._1).distinct
    (ncmds, backoff)
  }

  override def getMaxHardErrorCount: Int = {
    val objsNotifyStrikes = configSvc.config.fetchCommandRrdObjects(pfCmd.id, Some(interval)).map { ou =>
      configSvc.objectVarNotifyStrikes(ou, None)
    }
    if (objsNotifyStrikes.nonEmpty) objsNotifyStrikes.min else {
      log.debug(s"SMGMonPfState.getPfMaxHardErrorCount(${pfCmd.id}): empty objsNotifyStrikes seq")
      configSvc.config.globalNotifyStrikes
    }
  }
}

object SMGMonPfState {
  def stateId(pfCmd: SMGPreFetchCmd, interval: Int): String = stateId(pfCmd.id, interval)
  def stateId(pfCmdId: String, interval:Int): String = s"$pfCmdId:$interval"
  def fetchParentStateId(pfOpt: Option[String], interval: Int, pluginId: Option[String]) =
    Some(pfOpt.map(pfid => SMGMonPfState.stateId(pfid, interval)).getOrElse(SMGMonRunState.stateId(interval, pluginId)))

}

class SMGMonRunState(val interval: Int,
                     val pluginId: Option[String],
                     val configSvc: SMGConfigService,
                     val monLog: SMGMonitorLogApi,
                     val notifSvc: SMGMonNotifyApi) extends SMGMonInternalState {

  override val id: String = SMGMonRunState.stateId(interval, pluginId)
  override def parentId: Option[String] = None

  override def ouids: Seq[String] = Seq()
  override def vixOpt: Option[Int] = None

  override def oid: Option[String] = None
  override def pfId: Option[String] = None

  override def text: String = currentState.desc

  private val pluginDesc = pluginId.map(s => s" (plugin - $s)").getOrElse("")

  def processOk(ts:Int): Unit = addState(SMGState(ts, SMGState.OK, s"interval $interval$pluginDesc - OK"), isInherited = false)

  def processOverlap(ts: Int): Unit = addState(
    SMGState(ts, SMGState.E_SMGERR, s"interval $interval$pluginDesc - overlapping runs"),
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
