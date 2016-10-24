package com.smule.smg

import java.io.{File, FileWriter}
import javax.inject.{Inject, Singleton}

import play.api.inject.ApplicationLifecycle

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import play.api.libs.json._

import scala.collection.mutable.ListBuffer
import scala.io.Source


/**
  * Created by asen on 7/5/16.
  */


case class SMGHealthState(stateVal: SMGState.Value, desc: String)

// local (mutable) object health state
class SMGObjectHealth(obju: SMGObjectUpdate,
                      configSvc: SMGConfigService,
                      monLog: SMGMonitorLogApi,
                      notifSvc: SMGMonNotifyApi,
                      monRef: SMGMonitor
                     ) {
  private val log = SMGLogger

  private var ou = obju

  def configUpdated(newObj: SMGObjectUpdate): Unit = {
    // TODO synchronize???
    ou = newObj
  }

  def getObju = ou

  def getStates = recentStates // recentStates.synchronized(recentStates)

  private val maxRecentStates = 3 // TODO get from configSvc
  val maxErrors = maxRecentStates // TODO get from alert config

  // these below are serialized
  protected var myIsAcknowleged = false
  protected var myIsSilenced = false
  protected var mySilencedUntil: Option[Int] = None

  protected val counterPrevValues = Array.fill[Double](ou.vars.size)(Double.NaN)
  protected var counterPrevTs = 0
  protected var wasPrefetchError = false

  // list of (timestamp, health state) tuples
  private def genInitialStates: List[(Int, List[SMGHealthState])] =
    List( (0, ou.vars.map(_ => SMGHealthState(SMGState.OK, "initial state"))) )
  private var recentStates: List[(Int, List[SMGHealthState])] =  genInitialStates
  protected var myBadSince: Array[Option[Int]] = Array.fill(ou.vars.size)(None)


  // "moving" long/short term stats for anomaly detection
  private val movingStats: Array[SMGMonValueMovingStats] = ou.vars.indices.map(vix => new SMGMonValueMovingStats(ou.id, vix, ou.interval)).toArray

  def isAcknowleged = myIsAcknowleged

  def isSilenced = {
    val sopt = mySilencedUntil // copy to avoid race condition
    if (sopt.isDefined && sopt.get < SMGState.tssNow) {
      myIsSilenced = false
      mySilencedUntil = None
    }
    myIsSilenced
  }

  def silencedUntil = mySilencedUntil

  def silence(action: SMGMonSilenceAction): Unit = {
    action.action match {
      case SMGMonSilenceAction.ACK | SMGMonSilenceAction.ACK_PF => {
        myIsAcknowleged = action.silence
      }
      case SMGMonSilenceAction.SILENCE | SMGMonSilenceAction.SILENCE_PF => {
        mySilencedUntil = if (action.silence) action.until else None
        myIsSilenced = action.silence
      }
    }
  }


  private def stateChangeDesc(curHs: SMGHealthState, prevHs: SMGHealthState) = {
    s"state: ${curHs.desc} (prev state: ${prevHs.desc})"
  }

  /**
    * This MUST be called when a new state was added but before any excess states were chopped
    * so it deals with maxErrrors + 1 states in the common case.
    */
  private def processObjectStateChanges(): Unit = {
    //TODO XXX this is one long and ugly function ...
    // try to keep this fast in the common case (no logs/alets)
    val curTs = recentStates.head._1
    val latestStates = recentStates.head._2  // the state which was just added
    val histStatesLst = if (recentStates.tail.isEmpty) {
      List(genInitialStates.head._2)
    } else recentStates.tail.take(maxErrors).map(_._2) // states except the current one which was just added
    val prevStates = histStatesLst.head
    val latestIsOK = latestStates.forall(_.stateVal == SMGState.OK)

    // get out of here if no actions needed
    val noActionsNeeded = latestIsOK && prevStates.forall(_.stateVal == SMGState.OK)
    if (noActionsNeeded)
      return

    // evaluate these below on as-needed basis in the logic after
    lazy val newMonVarStates = monVarStates(None)
    lazy val newAggMonState = SMGMonStateAgg(newMonVarStates, SMGMonStateAgg.objectsUrlFilter(Seq(getObju.id)))
    lazy val wasFetchError = prevStates.forall(_.stateVal == SMGState.E_FETCH)
    lazy val isFetchError  = latestStates.forall(_.stateVal == SMGState.E_FETCH)

    // helper functions to count number of error repeats for given var
    def histErrorsRepeatCount(vix: Int): Int = {
      val lastOkIx = histStatesLst.indexWhere(hsl => hsl(vix).stateVal == SMGState.OK)
      if (lastOkIx == -1)
        maxErrors
      else
        lastOkIx
    }
    def errorsRepeatCount(vix: Int): Int = {
      if (latestStates(vix).stateVal == SMGState.OK)
        0
      else {
        histErrorsRepeatCount(vix) + 1
      }
    }

    // actual processing below

    if (latestIsOK) { // this means there is some recovery
      if (wasFetchError) {
        if (!wasPrefetchError) { // pre-fetch recovery has been logged already
          // log a single message for the recovery
          monLog.logMsg(
            SMGMonitorLogMsg(SMGMonitorLogMsg.fromObjectState(newAggMonState.currentStateVal), curTs,
              stateChangeDesc(latestStates.head, prevStates.head), 1, isHard = true, Seq(getObju.id), None, SMGRemote.local)
          )
        }
      } else {
        // log a message for each var recovery
        newMonVarStates.foreach { mso =>
          val vix = mso.ix
          if (latestStates(vix).stateVal != prevStates(vix).stateVal) {
            val wasHard = histErrorsRepeatCount(vix) >= maxErrors
            monLog.logMsg(
              SMGMonitorLogMsg(SMGMonitorLogMsg.fromObjectState(mso.currentStateVal), curTs,
                stateChangeDesc(latestStates(vix), prevStates(vix)), 1, isHard = wasHard,
                Seq(getObju.id), Some(vix), SMGRemote.local)
            )
          }
        }
      }
      wasPrefetchError = false
      // XXX all of these are triggered regardless of whether this is a recovery from a fetch error
      newMonVarStates.foreach { mso =>
        notifSvc.sendRecoveryMessages(mso)
      }
      notifSvc.sendRecoveryMessages(newAggMonState)
      return
    }

    // here latest state is not OK

    //this is handled already when processing the pf message in SMGMonitor
    val isPreFetchError = isFetchError &&
      getObju.preFetch.isDefined &&
      monRef.fetchErrorState(getObju.preFetch.get).isDefined
    if (isPreFetchError) {
      wasPrefetchError = true
      return
    }
    wasPrefetchError = false

    // fetch errors generate single msg for all var mon states
    if (isFetchError) {
      val wasHard = histStatesLst.forall(_.forall(_.stateVal != SMGState.OK))
      if (newAggMonState.shouldNotify) {
        val (notifCmds, backoff) = configSvc.objectVarNotifyCmdsAndBackoff(getObju, None, SMGMonNotifySeverity.UNKNOWN)
        if (wasHard) {
          notifSvc.checkAndResendAlertMessages(newAggMonState, backoff)
        } else {
          notifSvc.sendAlertMessages(newAggMonState, notifCmds)
        }
      }
      val repeat = newMonVarStates.map(mso => errorsRepeatCount(mso.ix)).max
      if (!wasHard) {
        monLog.logMsg(
          SMGMonitorLogMsg(SMGMonitorLogMsg.fromObjectState(newAggMonState.currentStateVal), curTs,
            stateChangeDesc(latestStates.head, prevStates.head), repeat, newAggMonState.isHard,
            Seq(getObju.id), None, SMGRemote.local)
        )
      } // else no log msg - continuous hard error
      return
    }

    // individual var states changes
    newMonVarStates.foreach { msov =>
      val vix = msov.ix
      val prevHs = prevStates(vix)
      if (msov.currentStateVal == SMGState.OK) {
        if (prevHs.stateVal != SMGState.OK) { // a recovery
          notifSvc.sendRecoveryMessages(msov)
          val wasHard = histErrorsRepeatCount(vix) >= maxErrors
          monLog.logMsg(
            SMGMonitorLogMsg(SMGMonitorLogMsg.fromObjectState(msov.currentStateVal), curTs,
              stateChangeDesc(latestStates(vix), prevStates(vix)), 1, isHard = wasHard,
              Seq(getObju.id), Some(vix), SMGRemote.local)
          )
        } // else do nothing - continuous OK states
      } else { // msov.currentStateVal != SMGState.OK
        // bad value state
        val isImmediateChange = msov.currentStateVal != prevHs.stateVal
        val wasNotHard = histErrorsRepeatCount(vix) < maxErrors
        lazy val mntype = SMGMonNotifySeverity.fromStateValue(msov.currentStateVal)
        lazy val (notifCmds, backoff) = configSvc.objectVarNotifyCmdsAndBackoff(getObju, Some(vix), mntype)
        if (isImmediateChange || wasNotHard) {
          if (msov.shouldNotify) { // is hard now
            notifSvc.sendAlertMessages(msov, notifCmds)
          }
          val repeat =  scala.math.min(errorsRepeatCount(vix), maxErrors)
          monLog.logMsg(
            SMGMonitorLogMsg(SMGMonitorLogMsg.fromObjectState(msov.currentStateVal), curTs,
              stateChangeDesc(latestStates(vix), prevStates(vix)), repeat, msov.isHard,
              Seq(getObju.id), Some(vix), SMGRemote.local)
          )
        } else {
          //no log msg - continuous hard error - just resend notifications if applicable
          notifSvc.checkAndResendAlertMessages(msov, backoff)
        }
      }
    }
  }

  private def addState(ts: Int, states: List[SMGHealthState]): Unit = {
    // prepend our new state
    recentStates = (ts,states) :: recentStates

    // update myBadSince accordingly
    states.zipWithIndex.foreach { t =>
      val hs = t._1
      val ix = t._2
      if (hs.stateVal == SMGState.OK)
        myBadSince(ix) = None
      else if (myBadSince(ix).isEmpty) myBadSince(ix) = Some(ts)
    }
    processObjectStateChanges()  // after recentStates have been updated but before truncated

    // drop obsolete state(s)
    while (recentStates.size > maxRecentStates) {
      recentStates = recentStates.dropRight(1)
    }

    // remove any acknowledgements if all states are OK
    if (states.forall(_.stateVal == SMGState.OK))
      myIsAcknowleged = false
  }

  private def processCounterUpdate(ts: Int, rawVals: List[Double]): Option[List[(Double,Option[String])]] = {
    val tsDelta = ts - counterPrevTs

    val ret = if (tsDelta > (ou.interval * 2)) {
      if (counterPrevTs > 0) {
        log.debug(s"SMGObjectHealth.processCounterUpdate(${ou.id}): Time delta is too big: $ts - $counterPrevTs = $tsDelta")
      }
      None
    } else if (tsDelta <= 0) {
      log.error(s"SMGObjectHealth.processCounterUpdate(${ou.id}): Non-positive time delta detected: $ts - $counterPrevTs = $tsDelta")
      None
    } else {
      val rates = rawVals.zip(counterPrevValues).zip(ou.vars).zipWithIndex.map { ttt =>
        val tt = ttt._1
        val t = tt._1
        val v = tt._2
        val varIx = ttt._2
        val maxr = v.get("max").map(_.toDouble)
        val r = (t._1 - t._2) / tsDelta
        if ((r < 0) || (maxr.isDefined && (maxr.get < r))){
          log.debug(s"SMGObjectHealth.processCounterUpdate(${ou.id}:$varIx): Counter overflow detected: p=${t._2} c=${t._1} r=$r maxr=$maxr")
          (Double.NaN, Some(s"Counter overflow: p=${t._2} c=${t._1} r=$r maxr=$maxr"))
        } else
          (r, None)
        }
      Some(rates)
    }
    rawVals.zipWithIndex.foreach(t => counterPrevValues(t._2) = t._1)
    counterPrevTs = ts
    ret
  }

  def processUpdate(ts: Int, rawVals: List[Double]): Unit = {
    val valsOpt = if (ou.rrdType == "COUNTER") {
          processCounterUpdate(ts, rawVals)
        } else Some(rawVals.map(rv => (rv, None)))
    if (valsOpt.isEmpty) {
      return
    }
    val vals = valsOpt.get
    val healthStates = vals.zipWithIndex.map { t =>
      val tvd = t._1
      val v = tvd._1
      val nanDesc = tvd._2.getOrElse("unknown")
      val ix = t._2

      val alertConfs = configSvc.objectValueAlertConfs(ou, ix)
      var ret : SMGHealthState = null
      if (v.isNaN) {
        ret = SMGHealthState(SMGState.E_ANOMALY, s"ANOM: value=NaN ($nanDesc)")
      } else if (alertConfs.nonEmpty) {
        val allAlertStates = alertConfs.map { alertConf =>
          var curRet: SMGHealthState = null
          val warnThreshVal = alertConf.warn.map(_.value).getOrElse(Double.NaN)
          val critThreshVal = alertConf.crit.map(_.value).getOrElse(Double.NaN)
          val descSx = s"( $warnThreshVal / $critThreshVal )"

          if (alertConf.crit.isDefined) {
            val alertDesc = alertConf.crit.get.checkAlert(v)
            if (alertDesc.isDefined)
              curRet = SMGHealthState(SMGState.E_VAL_CRIT, s"CRIT: ${alertDesc.get} : $descSx")
          }
          if ((curRet == null) && alertConf.warn.isDefined ) {
            val alertDesc = alertConf.warn.get.checkAlert(v)
            if (alertDesc.isDefined)
              curRet = SMGHealthState(SMGState.E_VAL_WARN, s"WARN: ${alertDesc.get} : $descSx")
          }

          if (alertConf.spike.isDefined) {
            // Update short/long term averages, to be used for spike/drop detection
            val ltMaxCounts = alertConf.spike.get.maxLtCnt(getObju.interval)
            val stMaxCounts = alertConf.spike.get.maxStCnt(getObju.interval)
            movingStats(ix).update(ts, v, stMaxCounts, ltMaxCounts)
            // check for spikes
            if (curRet == null) {
              val alertDesc = alertConf.spike.get.checkAlert(movingStats(ix), stMaxCounts, ltMaxCounts)
              if (alertDesc.isDefined)
                curRet = SMGHealthState(SMGState.E_ANOMALY, s"ANOM: ${alertDesc.get}")
            }
          } else movingStats(ix).reset()

          if (curRet == null) {
            curRet = SMGHealthState(SMGState.OK, s"OK: value=$v : $descSx")
          }
          curRet
        }
        ret = allAlertStates.maxBy(_.stateVal)
      }
      if (ret == null) {
        ret = SMGHealthState(SMGState.OK, s"OK: value=$v")
      }
      if (ret.stateVal != SMGState.OK) {
        log.debug(s"MONITOR: ${ou.id} : $ix : $ret")
      }
      ret
    }
    addState(ts, healthStates)
  }

  def processFetchError(ts: Int, exitCode:Int, errors: Seq[String]): Unit = {
    val errorMsg = s"Fetch error: exit=$exitCode, OUTPUT: " + errors.mkString(" ")
    val healthStates = getObju.vars.map(v => SMGHealthState(SMGState.E_FETCH, errorMsg))
    addState(ts, healthStates)
  }

  def monVarStates(ov: Option[SMGObjectView]): Seq[SMGMonStateObjVar]  = {
    val states = getStates.take(maxErrors)
    if (states.isEmpty) return List()
    // TODO may be discover values for cdef vars?
    val varIndices = if (ov.isDefined && ov.get.graphVarsIndexes.nonEmpty) ov.get.graphVarsIndexes else getObju.vars.indices
    varIndices.map { ouvix =>
      val v = getObju.vars(ouvix)
      val varStates = states.map { t =>
        // XXX apparently t._2 can be empty after restart causing an exception
        if (t._2.isDefinedAt(ouvix)) {
          SMGState(t._1, t._2(ouvix).stateVal, t._2(ouvix).desc)
        } else {
          log.warn(s"SMGObjectHealth.monVarStates($ov): bad states tumple: $t")
          SMGState(t._1, SMGState.E_ANOMALY, "state not initialized")
        }
      }
      SMGMonStateObjVar(getObju.id, ouvix,
        configSvc.config.viewObjectsByUpdateId.getOrElse(getObju.id,List()).map(_.id).toList,
        getObju.preFetch, getObju.title, v.getOrElse("label", "ds" + ouvix),
        isAcknowleged, isSilenced, silencedUntil, varStates, myBadSince(ouvix), SMGRemote.local)
    }
  }

  def monObjState(): SMGMonStateAgg = SMGMonStateAgg(monVarStates(None), SMGMonStateAgg.objectsUrlFilter(Seq(getObju.id)))


  def serialize: JsValue = {
    val mm = mutable.Map[String,JsValue](
      "sts" -> Json.toJson(SMGState.tssNow),
      "ouid" -> Json.toJson(getObju.id)
    )
    if (myIsAcknowleged) mm += ("ack" -> Json.toJson("true"))
    val suntOpt = mySilencedUntil // copy to avoid race condition
    if (myIsSilenced) mm += ("slc" -> Json.toJson("true"))
    if (suntOpt.isDefined) mm += ("sunt" -> Json.toJson(suntOpt.get))
    val rs = recentStates.map{ t =>
      val ts = t._1
      val hslst = t._2
      Json.toJson(
        Map(
          "ts" -> Json.toJson(ts),
          "hs" -> Json.toJson(hslst.map(hs => Json.toJson(Map("s" -> hs.stateVal.toString, "d" -> hs.desc))))
        )
      )
    }
    mm += ("rs" -> Json.toJson(rs))
    if (wasPrefetchError) mm += ("wpfe" -> Json.toJson("true"))

    if (counterPrevTs != 0) {
      mm += ("cpts" -> Json.toJson(counterPrevTs))
      mm += ("cpvs" -> Json.toJson(counterPrevValues))
    }

    mm += ("mvstats" -> Json.toJson(movingStats.map(_.serialize)))

    Json.toJson(mm.toMap)
  }
}

object SMGObjectHealth {
  private val log = SMGLogger

  def deserialize(src: JsValue,
                  configSvc: SMGConfigService,
                  monLog: SMGMonitorLogApi,
                  notifSvc: SMGMonNotifyApi,
                  monRef: SMGMonitor):Option[SMGObjectHealth] = {
    try {
      val sts = (src \ "sts").get.as[Int]
      val statsAge = SMGState.tssNow - sts
      val ouid = (src \ "ouid").get.as[String]
      val oopt = configSvc.config.updateObjectsById.get(ouid)
      if (oopt.isEmpty)
        return None
      val ou = oopt.get

      // sanity check, should never happen
      if (ouid != ou.id){
        log.error(s"SMGObjectHealth.deserialize: ouid != refObj.id ($ouid != ${ou.id})")
        return None
      }

      // our new health object
      val ret = new SMGObjectHealth(ou, configSvc, monLog, notifSvc, monRef)

      // acknowledgement status, age doesn't matter
      ret.myIsAcknowleged = (src \ "ack").toOption.map(_.as[String]).getOrElse("false") == "true"
      // slienced status, isSilenced will discard timed silencing
      ret.myIsSilenced = (src \ "slc").toOption.map(_.as[String]).getOrElse("false") == "true"
      ret.mySilencedUntil = (src \ "sunt").toOption.map(_.as[Int])

      // recent states, ignored if too old
      val rs = (src \ "rs").as[List[JsValue]].map { jsv =>
          val ts = (jsv \ "ts").as[Int]
          val hs = (jsv \ "hs").as[List[JsValue]].map { jshs =>
             SMGHealthState(SMGState.withName((jshs \ "s").get.as[String]), (jshs \ "d").get.as[String])
          }
          (ts, hs)
        }
      if (rs.nonEmpty) {
        val diff = SMGState.tssNow - rs.head._1
        if (diff < ou.interval * 10) {
          ret.wasPrefetchError = (src \ "wpfe").asOpt[String].getOrElse("false") == "true"
          ret.recentStates = rs
        } else
          log.debug(s"SMGObjectHealth.deserialize: discarding recent states for $ouid: $diff seconds old")
      }

      // previous counter values, ignored if older than 2 * interval
      val cptsJs = src \ "cpts"
      if (cptsJs.toOption.isDefined) {
        val cpts = cptsJs.as[Int]
        if (SMGState.tssNow - cpts < ou.interval * 2) {
          val cpvs = (src \ "cpvs").as[List[Double]]
          ret.counterPrevTs = cpts
          cpvs.zipWithIndex.foreach(t => ret.counterPrevValues(t._2) = t._1)
        }
      }

      if ( statsAge < 3600) {
        val mvstsJs = src \ "mvstats"
        if (mvstsJs.toOption.isDefined) {
          mvstsJs.as[List[JsValue]].zipWithIndex.foreach { t =>
            val jsv = t._1
            val ix = t._2
            if (ix < ret.movingStats.length) {
              ret.movingStats(ix) = SMGMonValueMovingStats.deserialize(jsv, ou.id, ix, ou.interval)
            }
          }
        }
      } else
        log.warn(s"SMGObjectHealth.deserialize: discarding moving stats for $ouid: $statsAge seconds old")
      Some(ret)
    } catch {
      case e: Throwable => {
        log.ex(e, "SMGObjectHealth.deserialize: exception")
        None
      }
    }
  }
}


case class SMGMonFetchErrorState(exitCode: Int, out: List[String], repeat: Int) {
  def serialize: JsValue = {
    Json.toJson(Map(
      "ext" -> Json.toJson(exitCode),
      "out" -> Json.toJson(out),
      "repeat" -> Json.toJson(repeat)
    ))
  }
}

object SMGMonFetchErrorState {

  def deserialize(src: JsValue): SMGMonFetchErrorState = {
    val ts = (src \ "ext").as[Int]
    val out = (src \ "out").as[List[String]]
    val repeat = (src \ "repeat").as[Int]
    SMGMonFetchErrorState(ts, out, repeat)
  }

  val unknown = SMGMonFetchErrorState(-1, List(), 0)

}

@Singleton
class SMGMonitor @Inject() (configSvc: SMGConfigService,
                            smg: GrapherApi,
                            remotes: SMGRemotesApi,
                            val monLogApi: SMGMonitorLogApi,
                            notifSvc: SMGMonNotifyApi,
                            lifecycle: ApplicationLifecycle) extends SMGMonitorApi
  with SMGDataFeedListener with SMGConfigReloadListener {

  configSvc.registerDataFeedListener(this)
  configSvc.registerReloadListener(this)

  val log = SMGLogger

  private val objectsState = TrieMap.empty[String, SMGObjectHealth]

  private val fetchErrorStates = TrieMap[String, SMGMonFetchErrorState]()

  private val runErrorStates = TrieMap[String,Int]() // not serialized

  private val MAX_STATES_PER_CHUNK = 2500

  private def serializeObjectsState: List[String] = {
    objectsState.toList.grouped(MAX_STATES_PER_CHUNK).map { chunk =>
      val om = chunk.map{ t =>
        val k = t._1
        val v = t._2
        (k, v.serialize)
      }
      Json.toJson(om.toMap).toString()
    }.toList
  }

  private def deserializeObjectsState(stateStr: String): Unit = {
    val jsm = Json.parse(stateStr).as[Map[String, JsValue]]
    jsm.foreach { t =>
      val oid = t._1
      val hobj = SMGObjectHealth.deserialize(t._2, configSvc, monLogApi, notifSvc, this)
      if (hobj.isDefined)
        objectsState(oid) = hobj.get
    }
  }

  private def serializeFetchErrorStates: String = {
    val om = fetchErrorStates.map { t =>
      val k = t._1
      val fes = t._2
      (k, fes.serialize)
    }
    Json.toJson(om.toMap).toString()
  }

  private def deserializeFetchErrorStates(stateStr: String): Unit = {
    try {
      val jsm = Json.parse(stateStr).as[Map[String, JsValue]]
      jsm.foreach { t =>
        val eid = t._1
        val fes = SMGMonFetchErrorState.deserialize(t._2)
        if (configSvc.config.updateObjectsById.contains(eid) || configSvc.config.preFetches.contains(eid))
          fetchErrorStates(eid) = fes
      }
    } catch {
      case t: Throwable => log.ex(t, "Unexpected error in deserializeFetchErrorStates")
    }
  }

  private def monStateDir = configSvc.config.globals.getOrElse("$monstate_dir", "monstate")
  private val MONSTATE_META_FILENAME = "meta.json"
  private val MONSTATE_BASE_FILENAME = "hobjects"
  private val FETCHERR_STATES_FILENAME = "ferrors.json"
  private val NOTIFY_STATES_FILENAME = "notify.json"

  private def monStateMetaFname = s"$monStateDir/$MONSTATE_META_FILENAME"
  private def monStateBaseFname = s"$monStateDir/$MONSTATE_BASE_FILENAME"
  private def fetchStatesFname = s"$monStateDir/$FETCHERR_STATES_FILENAME"
  private def notifyStatesFname = s"$monStateDir/$NOTIFY_STATES_FILENAME"

  def saveStateToDisk(): Unit = {
    try {
      log.info("SMGMonitor.saveStateToDisk BEGIN")
      new File(monStateDir).mkdirs()
      val statesLst = serializeObjectsState
      statesLst.zipWithIndex.foreach { t =>
        val stateStr = t._1
        val ix = t._2
        val suffix = if (ix == 0) "" else s".$ix"
        val monStateFname = s"$monStateBaseFname$suffix.json"
        log.info(s"SMGMonitor.saveStateToDisk $monStateFname")
        val fw = new FileWriter(monStateFname, false)
        try {
          fw.write(stateStr)
        } finally fw.close()
      }
      val metaStr = Json.toJson(Map("stateFiles" -> statesLst.size.toString)).toString()
      val fw1 = new FileWriter(monStateMetaFname, false)
      try {
        fw1.write(metaStr)
      } finally fw1.close()
      val fetchStatesStr = serializeFetchErrorStates
      val fw2 = new FileWriter(fetchStatesFname, false)
      try {
        fw2.write(fetchStatesStr)
      } finally fw2.close()
      val notifyStatesStr = notifSvc.serializeState().toString()
      val fw3 = new FileWriter(notifyStatesFname, false)
      try {
        fw3.write(notifyStatesStr)
      } finally fw3.close()

      log.info("SMGMonitor.saveStateToDisk END")
    } catch {
      case t: Throwable => log.ex(t, "Unexpected exception in SMGMonitor.saveStateToDisk")
    }
  }

  def parseStateMetaData(metaStr: String): Map[String,String] = {
    Json.parse(metaStr).as[Map[String,String]]
  }

  private def loadStateFromDisk(): Unit = {
    log.info("SMGMonitor.loadStateFromDisk BEGIN")
    try {
      val metaD: Map[String,String] = if (new File(monStateMetaFname).exists()) {
        val metaStr = Source.fromFile(monStateMetaFname).getLines().mkString
        parseStateMetaData(metaStr)
      } else Map()
      val numStateFiles = metaD.getOrElse("stateFiles", "1").toInt
      (0 until numStateFiles).foreach { ix =>
        val suffix = if (ix == 0) "" else s".$ix"
        val monStateFname = s"$monStateBaseFname$suffix.json"
        if (new File(monStateFname).exists()) {
          log.info(s"SMGMonitor.loadStateFromDisk $monStateFname")
          val stateStr = Source.fromFile(monStateFname).getLines().mkString
          deserializeObjectsState(stateStr)
        }
      }
      if (new File(fetchStatesFname).exists()) {
        val stateStr = Source.fromFile(fetchStatesFname).getLines().mkString
        deserializeFetchErrorStates(stateStr)
      }
      if (new File(notifyStatesFname).exists()) {
        val stateStr = Source.fromFile(notifyStatesFname).getLines().mkString
        notifSvc.deserializeState(stateStr)
      }
      log.info(s"SMGMonitor.loadStateFromDisk END - ${objectsState.size} objects, ${fetchErrorStates.size} fetch error states loaded")
    } catch {
      case x:Throwable => log.ex(x, "SMGMonitor.loadStateFromDisk ERROR")
    }
  }

  lifecycle.addStopHook { () =>
    Future.successful {
      saveStateToDisk()
    }
  }
  loadStateFromDisk()

  override def reload(): Unit = {
    val objsById = configSvc.config.updateObjects.groupBy(_.id)
    objectsState.toList.foreach { t =>
      val newObj = objsById.get(t._1)
      if (newObj.isDefined && newObj.get.nonEmpty)
        t._2.configUpdated(newObj.get.head)
      else {
        log.warn(s"Removing health state for non-existing object: ${t._1}")
        objectsState.remove(t._1)
        fetchErrorStates.remove(t._1)
      }
    }
    fetchErrorStates.keys.toList.foreach { eid =>
      if (!configSvc.config.updateObjectsById.contains(eid) && !configSvc.config.preFetches.contains(eid)){
        log.warn(s"Removing fetch error state for non-existing object: ${eid}")
        fetchErrorStates.remove(eid)
      }
    }
    notifSvc.configReloaded()
  }

  override def receiveObjMsg(msg: SMGDFObjMsg): Unit = {
    log.debug("SMGMonitor: receive: " + msg)
    val hobj = objectsState.getOrElseUpdate(msg.obj.id, { new SMGObjectHealth(msg.obj, configSvc, monLogApi, notifSvc, this) })
    if ((msg.exitCode != 0) || msg.errors.nonEmpty) {
      val prevFesCnt = fetchErrorStates.get(msg.obj.id).map(_.repeat).getOrElse(0)
      fetchErrorStates(msg.obj.id) = SMGMonFetchErrorState(msg.exitCode, msg.errors, prevFesCnt + 1)
      hobj.processFetchError(msg.ts, msg.exitCode, msg.errors)
    }
    else {
      fetchErrorStates.remove(msg.obj.id)
      hobj.processUpdate(msg.ts, msg.vals)
    }
  }

  val runErrorMaxStrikes = 2 // TODO read from config?

  override def receiveRunMsg(msg: SMGDFRunMsg): Unit = {
    log.debug("SMGMonitor: receive: " + msg)
    val rsKey =  if (msg.pluginId.isDefined) msg.pluginId.get else s"smg_intvl_${msg.interval}"
    lazy val msgStr = if (msg.pluginId.isDefined)
      s"Plugin ${msg.pluginId} run interval ${msg.interval}: "
    else
      s"SMG run interval ${msg.interval}: "
    val prevCount = runErrorStates.getOrElseUpdate(rsKey, 0)
    if (msg.isOverlap) {
      val newCount = prevCount + 1
      val isHard = newCount >= runErrorMaxStrikes
      val lmsg = SMGMonitorLogMsg(SMGMonitorLogMsg.OVERLAP,msg.ts, msgStr + msg.errors.mkString(" "),
        newCount, isHard, Seq(), None, SMGRemote.local)
      monLogApi.logMsg(lmsg)
      runErrorStates(rsKey) = newCount
      if (isHard){
        val ncmds = configSvc.globalNotifyCmds(SMGMonNotifySeverity.SMGERR)
        notifSvc.sendAlertMessages(monStateOverlap(rsKey), ncmds) // TODO XXX these notify w/o backoff
      }
    } else {
      if (prevCount > 0){
        val lmsg = SMGMonitorLogMsg(SMGMonitorLogMsg.RECOVERY,msg.ts, msgStr + " Recovery", 1, true,
          Seq(), None, SMGRemote.local)
        monLogApi.logMsg(lmsg)
        //if (prevCount >= runErrorMaxStrikes) // XXX no need to check if it was hard?
        notifSvc.sendRecoveryMessages(monStateOverlap(rsKey))
      }
      runErrorStates(rsKey) = 0
    }
  }

  def pfErrorMaxStrikes(pfId: String) = 3 // TODO read from config

  override def receivePfMsg(msg: SMGDFPfMsg): Unit = {
    log.debug("SMGMonitor: receive: " + msg)
    val maxRepeat = pfErrorMaxStrikes(msg.pfId)
    lazy val hObjs = msg.objs.map(ou => objectsState.get(ou.id)).filter(_.isDefined).map(_.get)
    lazy val objsMonStates = hObjs.flatMap(hobj => hobj.monVarStates(None))
    def pfState(repeat: Int) = SMGMonStatePreFetch(objsMonStates, Some(msg.pfId), msg.exitCode, msg.errors, repeat)
    if (msg.exitCode == 0) {
      val oldState = fetchErrorStates.remove(msg.pfId)
      if (oldState.isDefined) { // recovery
        val wasHard = oldState.get.repeat >= maxRepeat
        notifSvc.sendRecoveryMessages(pfState(1))
        val lmsg = SMGMonitorLogMsg(SMGMonitorLogMsg.RECOVERY, msg.ts,
          s"Pre-fetch succeeded. Previous state (exitCode=${oldState.get.exitCode}): " + oldState.get.out.mkString("\n"),
          1, isHard = wasHard, msg.objs.map(_.id), None, SMGRemote.local)
        monLogApi.logMsg(lmsg)
      } // else continuos OK state, nothing to do
    } else { // error
      val prevFesCnt = fetchErrorStates.get(msg.pfId).map(_.repeat).getOrElse(0)
      val repeat = scala.math.min(1 + prevFesCnt, maxRepeat)
      val isHard = repeat >= maxRepeat
      val wasHard = prevFesCnt >= maxRepeat
      val isSilencedOrAcked = objsMonStates.forall(_.isSilencedOrAcked) // silenced if all are silenced
      if (isHard && !isSilencedOrAcked) {
        val tuples = msg.objs.map(ou => configSvc.objectVarNotifyCmdsAndBackoff(ou,None, SMGMonNotifySeverity.UNKNOWN))
        lazy val backoff = tuples.map(_._2).max
        lazy val ncmds = tuples.flatMap(_._1).distinct
        if (wasHard) {
          notifSvc.checkAndResendAlertMessages(pfState(repeat), backoff)
        } else {
          notifSvc.sendAlertMessages(pfState(repeat), ncmds)
        }
      }
      fetchErrorStates(msg.pfId) = SMGMonFetchErrorState(msg.exitCode, msg.errors, repeat)
      if (prevFesCnt < maxRepeat) {
        val lmsg = SMGMonitorLogMsg(SMGMonitorLogMsg.FETCHERR, msg.ts,
          s"Pre-fetch error (exitCode=${msg.exitCode}): " + msg.errors.mkString("\n"),
          repeat, isHard, msg.objs.map(_.id), None, SMGRemote.local)
        monLogApi.logMsg(lmsg)
      }
    }
  }

  def fetchErrorState(k: String): Option[SMGMonFetchErrorState] = fetchErrorStates.get(k)

  private def expandOvs(ovs: Seq[SMGObjectView]): Seq[SMGObjectView] = {
    ovs.flatMap(ov => if (ov.isAgg) ov.asInstanceOf[SMGAggObjectView].objs else Seq(ov))
  }


  private def localNonAgObjectStates(ov: SMGObjectView): Seq[SMGMonStateObjVar]= {
    val hobjOpt = objectsState.get(ov.ouId)
    if (hobjOpt.isEmpty)
      List()
    else
      hobjOpt.get.monVarStates(Some(ov))
  }

  def localObjectViewsState(ovs: Seq[SMGObjectView]): Map[String,Seq[SMGMonStateObjVar]] = {
    ovs.map { ov =>
      val seq = if (ov.isAgg) {
        ov.asInstanceOf[SMGAggObjectView].objs.flatMap(localNonAgObjectStates)
      } else localNonAgObjectStates(ov)
      (ov.id, seq)
    }.toMap
  }


  override def objectViewStates(ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonStateObjVar]]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    val byRemote = expandOvs(ovs).groupBy(ov => SMGRemote.remoteId(ov.id))
    val futs = byRemote.flatMap{ t =>
      val rmtId = t._1
      val myOvs = t._2
      if (rmtId == SMGRemote.local.id) {
        Seq(Future {
          localObjectViewsState(myOvs)
        })
      } else {
        Seq(remotes.objectViewsStates(rmtId, myOvs))
      }
    }
    Future.sequence(futs).map { maps =>
      val retMap = if (maps.isEmpty) {
        log.error("objectViewStates: maps.isEmpty")
        Map[String,Seq[SMGMonStateObjVar]]()
      } else if (maps.tail.isEmpty)
        maps.head
      else {
        var ret = mutable.Map[String, Seq[SMGMonStateObjVar]]()
        maps.foreach(m => ret ++= m)
        ret.toMap
      }
      val ret = mutable.Map[String, Seq[SMGMonStateObjVar]]()
      ovs.foreach { ov =>
        if (ov.isAgg) {
          ret(ov.id) = ov.asInstanceOf[SMGAggObjectView].objs.flatMap( o => retMap.getOrElse(o.id, Seq()))
        } else
          ret(ov.id) = retMap.getOrElse(ov.id, Seq())
      }
      ret.toMap
    }
  }

  private def nonAgObjectMonStates(ov: SMGObjectView): Seq[SMGMonStateObjVar] = {
    val hobjOpt = objectsState.get(ov.ouId)
    if (hobjOpt.isEmpty)
      List()
    else
      hobjOpt.get.monVarStates(Some(ov))
  }

  def monStateOverlap(key: String) = SMGMonStateGlobal("SMG Run Error", key, SMGState( SMGState.tssNow, SMGState.E_SMGERR, "overlapping runs"))

  override def localProblems(includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Seq[SMGMonState] = {
    val glErrs = runErrorStates.toList.filter(t => t._2 > 0).map { t =>
      monStateOverlap(t._1)
    }
    // get all object errors but group common pre-fetch errors together
    val objectErrs = objectsState.values.filter(_.getStates.head._2.exists(_.stateVal != SMGState.OK)).flatMap { hso =>
      hso.monVarStates(None).filter { ms =>
        ( (ms.currentStateVal != SMGState.OK) &&
          (includeSoft || ms.isHard) &&
          (includeAcked || !ms.isAcked) &&
          (includeSilenced || !ms.isSilenced) ) || ( includeSilenced && (ms.currentStateVal == SMGState.OK))
      }.map(ms => (ms, hso.getObju.preFetch.getOrElse("")))
    }.toList
    val byPf = objectErrs.groupBy { _._2 }.map(t => (t._1, t._2.map(_._1))).filter(_._1 != "")
    val mutByPf = mutable.Map(byPf.toSeq: _*)
    val ordered = ListBuffer[SMGMonState]()
    objectErrs.foreach { t =>
      val ms = t._1
      val pfId = t._2
      if ((ms.currentState.state == SMGState.E_FETCH) && (pfId != "")) {
        if (mutByPf.contains(pfId)) {
          val pfState = fetchErrorStates.getOrElse(pfId, SMGMonFetchErrorState.unknown)
          val pfRepeat = scala.math.min(pfErrorMaxStrikes(pfId), pfState.repeat + mutByPf(pfId).map(_.errorRepeat).max)
          val pfMs = SMGMonStatePreFetch(mutByPf(pfId), Some(pfId), pfState.exitCode, pfState.out, pfRepeat)
          ordered += pfMs
          mutByPf.remove(pfId)
        }
      } else {
        ordered += ms
      }
    }
    val orderedBySeverety = ordered.groupBy(_.severity)
    glErrs ++ orderedBySeverety.keys.toList.sortBy(-_).flatMap(sv => orderedBySeverety(sv))
  }

  override def problems(includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Future[Seq[(SMGRemote, Seq[SMGMonState])]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    val remoteFuts = configSvc.config.remotes.map { rmt =>
      remotes.monitorIssues(rmt.id, includeSoft, includeAcked, includeSilenced).map((rmt,_))
    }
    val localFut = Future {
      (SMGRemote.local, localProblems(includeSoft, includeAcked, includeSilenced))
    }
    val allFuts = Seq(localFut) ++ remoteFuts
    Future.sequence(allFuts)
  }


  private def monPrefetchState(pfId: String, monVarStates: Option[Seq[SMGMonStateObjVar]]): Option[SMGMonStatePreFetch] = {
    fetchErrorStates.get(pfId).map { pfState =>
      val myMonVarStates = if (monVarStates.isDefined)
        monVarStates.get
      else
        configSvc.config.updateObjects.filter(_.preFetch.contains(pfId)).map{ o =>
          objectsState.get(o.id).map(_.monVarStates(None))
        }.filter(_.isDefined).flatMap(_.get)
      SMGMonStatePreFetch(myMonVarStates, Some(pfId), pfState.exitCode, pfState.out, pfState.repeat)
    }
  }


  def silenceLocalObject(ouid:String, action: SMGMonSilenceAction):Boolean = {
    val hobjs = action.action match {
      case SMGMonSilenceAction.SILENCE_PF | SMGMonSilenceAction.ACK_PF => {
        configSvc.config.updateObjects.filter(_.preFetch.contains(ouid)).map(o => objectsState.get(o.id))
      }
      case _ => Seq(objectsState.get(ouid))
    }
    var ret = true
    hobjs.foreach { hobj =>
      if (hobj.isDefined) {
        hobj.get.silence(action)
      } else {
        ret = false
      }
    }
    // send aclnowledgemenet notifications if applicable
    def errorMsg(state: String) = s"SMGMonitor.silenceLocalObject: Unexpected state ($state): ouid=$ouid action=$action hobjs=$hobjs"
    if (action.action == SMGMonSilenceAction.ACK && action.silence) {
      if (ret && hobjs.size == 1) {
        val monObjState = hobjs.head.get.monObjState()
        notifSvc.sendAcknowledgementMessages(monObjState)
        hobjs.head.get.monVarStates(None).foreach(notifSvc.sendAcknowledgementMessages)
      } else {
        log.error(errorMsg("ret && hobjs.size == 1"))
      }
    } else if (action.action == SMGMonSilenceAction.ACK_PF && action.silence) {
      if (ret && hobjs.nonEmpty) {
        val monStates = hobjs.flatMap(_.get.monVarStates(None))
        val monPfState = monPrefetchState(ouid, Some(monStates))
        if (monPfState.isDefined) {
          notifSvc.sendAcknowledgementMessages(monPfState.get)
          monPfState.get.lst.foreach(notifSvc.sendAcknowledgementMessages)
        } else {
          log.error(errorMsg("monPfState.isDefined"))
        }
      } else {
        log.error(errorMsg("ret && hobjs.nonEmpty"))
      }
    }
    ret
  }

  def silenceObject(ouid:String, action: SMGMonSilenceAction): Future[Boolean] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    if (SMGRemote.isRemoteObj(ouid)) {
      remotes.monitorSilence(ouid, action)
    } else {
      Future {
        silenceLocalObject(ouid, action)
      }
    }
  }

  private def condenseHeatmapStates(allStates: Seq[SMGMonStateObjVar], maxSize: Int): (List[SMGMonState], Int) = {
    val chunkSize = (allStates.size / maxSize) + (if (allStates.size % maxSize == 0) 0 else 1)
    val lst = allStates.grouped(chunkSize).map { chunk =>
      SMGMonStateAgg(chunk, SMGMonStateAgg.objectsUrlFilter(chunk.map(_.ouid)))
    }
    (lst.toList, chunkSize)
  }


  override def localHeatmap(flt: SMGFilter, maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): SMGMonHeatmap = {
    // TODO include global/run issues?
    val objList = smg.getFilteredObjects(flt).filter(o => SMGRemote.isLocalObj(o.id)) // XXX or clone the filter with empty remote?
    val objsSlice = objList.slice(offset.getOrElse(0), offset.getOrElse(0) + limit.getOrElse(objList.size))
    val allStates = objsSlice.flatMap( ov => nonAgObjectMonStates(ov))
    val ct = if (maxSize.isDefined && allStates.nonEmpty) condenseHeatmapStates(allStates, maxSize.get) else (allStates.toList, 1)
    SMGMonHeatmap(ct._1, ct._2)
  }

  override def heatmap(flt: SMGFilter, maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[Seq[(SMGRemote, SMGMonHeatmap)]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    val myRemotes = configSvc.config.allRemotes.filter { rmt =>
      val fltRemoteId = flt.remote.getOrElse(SMGRemote.local.id)
      (fltRemoteId == SMGRemote.wildcard.id) || (rmt.id == fltRemoteId)
    }
    val futs = myRemotes.map { rmt =>
      if (rmt== SMGRemote.local)
        Future {
          (rmt, localHeatmap(flt, maxSize, offset, limit))
        }
      else
        remotes.heatmap(rmt.id, flt, maxSize, offset, limit).map(mh => (rmt, mh))

    }
    Future.sequence(futs)
  }

  override  def inspect(ov:SMGObjectView): Option[String] = {
    val ouid = ov.refObj.map(_.id).getOrElse(ov.id)
    objectsState.get(ouid).map { hobj =>
      hobj.serialize.toString()
    }
  }

}
