package com.smule.smg

import java.util.Date
import javax.inject.{Inject, Singleton}

import play.api.libs.json.{JsValue, Json}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by asen on 8/31/16.
  */

//case class SMGNotificationMsg(monState: SMGMonState, prevMonState: Option[SMGMonState],  rcpts: List[String])

object SMGMonNotifySeverity extends Enumeration {
  val RECOVERY, ACKNOWLEDGEMENT, ANOMALY, WARNING, UNKNOWN, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED = Value

  def fromStateValue(sv: SMGState.Value): SMGMonNotifySeverity.Value = {
    sv match {
      case SMGState.OK => this.RECOVERY
      case SMGState.WARNING => this.WARNING
      case SMGState.CRITICAL => this.CRITICAL
      case SMGState.UNKNOWN => this.UNKNOWN
      case SMGState.ANOMALY => this.ANOMALY
      case _ => this.SMGERR
//        ACKNOWLEDGEMENT, THROTTLED and UNTHROTTLED are special
    }
  }
}

case class SMGMonNotifyCmd(id:String, command: String, timeoutSec: Int) {

  private def escapeSingleQuotes(str: String): String = {
    str.replaceAll("'", "\\'")
  }

  def alert(severity: SMGMonNotifySeverity.Value, alertKey: String, subjStr: String, bodyStr: String): Unit = {
    // TODO obsolete passing alert props via cmd line and use env instead
    val cmdStr = s"$command '${severity.toString}' '${escapeSingleQuotes(alertKey)}'"
    val myEnv = Map(
      "SMG_ALERT_SEVERITY" -> severity.toString,
      "SMG_ALERT_KEY" -> alertKey,
      "SMG_ALERT_SUBJECT" -> subjStr,
      "SMG_ALERT_BODY" -> bodyStr
    )
    SMGCmd.runCommand(cmdStr, timeoutSec, myEnv)
  }
}

trait SMGMonNotifyApi {

  def sendAlertMessages(monState: SMGMonState,  ncmds:  Seq[SMGMonNotifyCmd], isImprovement: Boolean): Future[Boolean]

  def checkAndResendAlertMessages(monState: SMGMonState, backOffSeconds: Int): Future[Boolean]

  def sendRecoveryMessages(monState: SMGMonState): Future[Boolean]

  def sendAcknowledgementMessages(monState: SMGMonState): Boolean

  def serializeState(): JsValue

  def deserializeState(srcStr: String): Unit

  def configReloaded(): Unit

  def tick(): Unit

  def muteAll(): Unit
  def isMuted: Boolean
  def unmuteAll(): Unit

}

case class SMGMonNotifyMsgData(severity: SMGMonNotifySeverity.Value,
                               alertKey: String,
                               subjStr: String,
                               bodyStr: String,
                               cmds: Seq[SMGMonNotifyCmd]
                              )

@Singleton
class SMGMonNotifySvc @Inject() (configSvc: SMGConfigService) extends SMGMonNotifyApi {

  private val log = SMGLogger

  implicit private val ec = ExecutionContexts.monitorCtx

  // alertKey -> cmds
  private val activeAlerts = TrieMap[String, List[SMGMonNotifyCmd]]()
  // alertKey -> last notif time
  private val activeAlertsLastTs = TrieMap[String, Int]()

  private val throttleSyncObj = new Object()
  private var currentThrottleTs = 0
  private var currentThrottleCnt = 0
  private val throttledMsgs = mutable.ListBuffer[SMGMonNotifyMsgData]()
  private var throttledIsSent = false
  private var myIsMuted: Boolean = false

  private def throttleInterval = configSvc.config.globals.getOrElse("$notify-throttle-interval", "3600").toInt
  private def throttleMaxCount = configSvc.config.globals.getOrElse("$notify-throttle-count", Int.MaxValue.toString).toInt


  val MAX_CONDENSED_SUBJECTS = 100

  private def realRunStateCommands(ncmds:  Seq[SMGMonNotifyCmd],
                                   severity: SMGMonNotifySeverity.Value,
                                   alertKey:String,
                                   subj: String,
                                   body: String): Boolean = {
    if (isMuted) {
      log.warn(s"SMGMonNotifySvc.notify: Muted state prevents notifications for ${alertKey} with subject $subj")
      return true
    }
    val rets = ncmds.map { c =>
      try {
        c.alert(severity, alertKey, subj, body)
        log.info(s"SMGMonNotifySvc.notify: notified ${c.id} for $subj")
        true
      } catch {
        case cex: SMGCmdException => {
          log.error(s"SMGMonNotifySvc.notify: notification failed $c subj=$subj body=$body")
          false
        }
        case t: Throwable => {
          log.ex(t, s"SMGMonNotifySvc.notify: unexpected error: $c subj=$subj body=$body")
          false
        }
      }
    }
    rets.exists(p => p)
  }

  def remoteSubjStr: String = configSvc.config.notifyRemoteId.map(s => s"($s)").getOrElse("")

  private def sendQueuedMsgs(msgsToCondense: Seq[SMGMonNotifyMsgData]): Future[Boolean] = {
    // send condensed states as a single msg, async
    if (msgsToCondense.isEmpty) Future { false }
    else Future {
      val allRcpts =  (configSvc.globalNotifyCmds(SMGMonNotifySeverity.SMGERR) ++
        msgsToCondense.flatMap(_.cmds)).distinct
      val msgSubj = s"UNTHROTTLED $remoteSubjStr - ${msgsToCondense.size} messages supressed during throttle"
      val condensedSubjects = msgsToCondense.map(_.subjStr).take(MAX_CONDENSED_SUBJECTS)
      val msgBody = s"$msgSubj. Some example subjects (displaying ${condensedSubjects.size}/${msgsToCondense.size}):\n\n" +
        condensedSubjects.mkString("\n") +
        s"\n\nURL: ${configSvc.config.notifyBaseUrl}/monitor#rt_${configSvc.config.notifyRemoteId.getOrElse("")}"
      realRunStateCommands(allRcpts,
        SMGMonNotifySeverity.UNTHROTTLED,
        SMGMonNotifySeverity.UNTHROTTLED.toString,
        msgSubj, msgBody)
    }
  }

  def flushQueuedMessages(nowHourTs: Int): Future[Boolean] = throttleSyncObj.synchronized {
    val msgsToCondense = throttledMsgs.toList
    throttledMsgs.clear()
    throttledIsSent = false
    currentThrottleCnt = 1
    currentThrottleTs = nowHourTs
    sendQueuedMsgs(msgsToCondense)
  }

  private def sendThrottledMsg(addNcmds: Seq[SMGMonNotifyCmd], cnt: Int, maxCnt: Int, throttledUntil: Int): Future[Boolean] = {
    val msgSubj = s"THROTTLED $remoteSubjStr message rate ($cnt) reached $maxCnt msgs/$throttleInterval sec"
    if (isMuted) {
      log.warn(s"SMGMonNotifySvc.notify: Muted state prevents sending of throttle messages with subj: $msgSubj")
      return Future { true }
    }
    val msgBody = s"$msgSubj: \n\n No more alert notifications will be sent " +
      s"until ${new Date(1000L * throttledUntil).toString}.\n\n" +
      s"Check ${configSvc.config.notifyBaseUrl}/monitor#${configSvc.config.notifyRemoteId.getOrElse("")}\n\n"
    val allCmds = (configSvc.globalNotifyCmds(SMGMonNotifySeverity.SMGERR) ++
      activeAlerts.values.flatten.toSeq ++ addNcmds).distinct
    val futs = allCmds.map { ncmd =>
      Future {
        try {
          ncmd.alert(SMGMonNotifySeverity.THROTTLED, SMGMonNotifySeverity.THROTTLED.toString, msgSubj, msgBody)
          log.info(s"SMGMonNotifySvc.sendThrottledMsg: Notified ${ncmd.id} for $msgSubj")
          true
        } catch {
          case cex: SMGCmdException => {
            log.error(s"SMGMonNotifySvc.sendThrottledMsg: notification failed $ncmd subj=$msgSubj body=$msgBody")
            false
          }
          case t: Throwable => {
            log.ex(t, s"SMGMonNotifySvc.sendThrottledMsg: unexpected error: $ncmd subj=$msgSubj body=$msgBody")
            false
          }
        }
      }
    }
    Future.sequence(futs).map(_.exists(p => p))
  }

  // Don't throttle acknowledgements and recoveries
  private val dontThrottleSeverities = Set(SMGMonNotifySeverity.RECOVERY, SMGMonNotifySeverity.ACKNOWLEDGEMENT)

  // return Future[True] if message should be sent
  private def updateThrottleCounters(severity: SMGMonNotifySeverity.Value,
                                     akey: String,
                                     subj: String,
                                     body: String,
                                     ncmds: Seq[SMGMonNotifyCmd]): Future[Boolean] = {
    if (dontThrottleSeverities.contains(severity)) {
      Future { true }
    } else {
      val intvl = throttleInterval
      val nowHourTs = (SMGRrd.tssNow / intvl) * intvl // rounded to beginning of throttle interval
      if (nowHourTs == currentThrottleTs) {
        throttleSyncObj.synchronized {
          currentThrottleCnt += 1
          if (currentThrottleCnt >= throttleMaxCount) {
            throttledMsgs += SMGMonNotifyMsgData(
              severity,
              akey, subj, body, ncmds)
            val shouldSendThrottle = if (!throttledIsSent) {
              throttledIsSent = true
              true
            } else false
            if (shouldSendThrottle)
              sendThrottledMsg(ncmds, currentThrottleCnt, throttleMaxCount, nowHourTs + intvl).map(b => false)
            Future {
              false
            }
          } else Future {
            true
          }
        }
      } else {
        // new throttle period
        flushQueuedMessages(nowHourTs).map(b => true)
      }
    }
  }

  def tick(): Unit = {
    val intvl = throttleInterval
    val nowHourTs = (SMGRrd.tssNow / intvl) * intvl // rounded to beginning of throttle interval
    if (nowHourTs != currentThrottleTs) {
      // new throttle period
      log.debug(s"SMGMonNotifySvc.tick: new throttle interval ($throttleMaxCount/$throttleInterval) throttledMsgs.isEmpty=${throttledMsgs.isEmpty}")
      flushQueuedMessages(nowHourTs)
    }
  }

  private def runStateCommandsAsync(monState: SMGMonState,  ncmds:  Seq[SMGMonNotifyCmd],
                                    isRepeat: Boolean, isImprovement: Boolean): Future[Boolean] = {
    val improvedStr = if (isImprovement) "(improved) " else ""
    val subj = improvedStr + monState.notifySubject(configSvc.config.notifyBaseUrl, configSvc.config.notifyRemoteId, isRepeat)
    val body = monState.notifyBody(configSvc.config.notifyBaseUrl, configSvc.config.notifyRemoteId)
    val severity = SMGMonNotifySeverity.fromStateValue(monState.currentStateVal)
    updateThrottleCounters(severity, monState.alertKey, subj, body, ncmds).map { shouldSend =>
      if (shouldSend) {
        realRunStateCommands(ncmds, SMGMonNotifySeverity.fromStateValue(monState.currentStateVal), monState.alertKey, subj, body)
      } else false
    }
  }

  override def sendAlertMessages(monState: SMGMonState,  ncmds:  Seq[SMGMonNotifyCmd],
                                 isImprovement: Boolean): Future[Boolean] = {
    if (ncmds.isEmpty) {
      Future {
        if (monState.currentStateVal > SMGState.ANOMALY) // TODO XXX temp logging to troubleshoot issue
          log.info(s"SMGMonNotifySvc.sendAlertMessages: empty recipients list for ${monState.id} (${monState.currentStateVal})")
        false
      }
    } else {
      val akey = monState.alertKey
      val toNotify = ncmds.distinct
      runStateCommandsAsync(monState, toNotify, isRepeat = false, isImprovement).map { msgWasSent =>
        if (msgWasSent) {
          val allNotified = toNotify.toSet ++ activeAlerts.getOrElse(akey, List()).toSet
          activeAlerts(akey) = allNotified.toList
          activeAlertsLastTs(akey) = SMGRrd.tssNow
          true
        } else false
      }
    }
  }

  override def checkAndResendAlertMessages(monState: SMGMonState, backOffSeconds: Int): Future[Boolean] = {
    val akey = monState.alertKey
    val tsNow = SMGRrd.tssNow
    val tsLast = activeAlertsLastTs.get(akey)
    val futs = ListBuffer[Future[Boolean]]()
    if (tsLast.isDefined && (tsNow - tsLast.get >= backOffSeconds)) {
      val cmds = activeAlerts.get(akey)
      activeAlertsLastTs(akey) = tsNow
      if (cmds.isDefined) futs += runStateCommandsAsync(monState, cmds.get, isRepeat = true, isImprovement = false)
    }
    if (futs.isEmpty)
      Future { false }
    else
      Future.sequence(futs.toList).map(bools => bools.exists(p => p))
  }

  override def sendRecoveryMessages(monState: SMGMonState): Future[Boolean] = {
    val akey = monState.alertKey
    val cmds = activeAlerts.remove(akey)
    activeAlertsLastTs.remove(akey)
    if (cmds.isDefined)
      // XXX setting isImprovement to false to avoid somewhat redundant "(improved) RECOVERY" strings in subj
      runStateCommandsAsync(monState, cmds.get, isRepeat = false, isImprovement = false)
    else
      Future { false }
  }

  def sendAcknowledgementMessages(monState: SMGMonState): Boolean = {
    val akey = monState.alertKey
    val cmds = activeAlerts.get(akey)
    if (cmds.isDefined) {
      val subj = "ACKNOWLEDGEMENT: " + monState.notifySubject(configSvc.config.notifyBaseUrl, configSvc.config.notifyRemoteId, isRepeat = false)
      val body = "ACKNOWLEDGEMENT: " + monState.notifyBody(configSvc.config.notifyBaseUrl, configSvc.config.notifyRemoteId)
      realRunStateCommands(cmds.get, SMGMonNotifySeverity.ACKNOWLEDGEMENT, monState.alertKey, subj, body)
    } else {
      false
    }
  }


  def serializeState(): JsValue = {
    Json.toJson(Map(
      "aa" -> Json.toJson(activeAlerts.toMap.map { t =>
        val ak = t._1
        val jsv = Json.toJson(t._2.map(_.id))
        (ak, jsv)
        }),
      "aalt" -> Json.toJson(activeAlertsLastTs.toMap),
      "ismtd" -> Json.toJson(myIsMuted)
    ))
  }

  def deserializeState(srcStr: String): Unit = {
    try {
      //activeAlerts.clear()
      val src = Json.parse(srcStr)
      (src \ "aa").as[Map[String,JsValue]].foreach { t =>
        val ncmds = t._2.as[List[String]].map { cmdid =>
          val cmdopt = configSvc.config.notifyCommands.get(cmdid)
          if (cmdopt.isEmpty) log.warn(s"SMGMonNotifySvc.deserializeState: non-existing command id: $cmdid")
          cmdopt
        }.filter(_.isDefined).map(_.get)
        if (ncmds.nonEmpty)
          activeAlerts(t._1) = ncmds
      }
      (src \ "aalt").as[Map[String,Int]].foreach { t => activeAlertsLastTs(t._1) = t._2}
      myIsMuted = (src \ "ismtd").asOpt[Boolean].getOrElse(false)
    } catch {
      case t: Throwable => log.ex(t, "Unexpected exception in SMGMonNotifySvc.deserializeState")
    }
  }

  def configReloaded(): Unit = {
    // TODO too dangerous?
    def cleanupMap(m: mutable.Map[String,_]) = {
      m.keys.toList.foreach { ac =>
        val ids = ac.split(",").toList
        ids.foreach { idlbl =>
          val id = idlbl.split(":")(0)
          if (!id.startsWith(SMGMonState.MON_STATE_GLOBAL_PX) &&
            !configSvc.config.updateObjectsById.contains(id) &&
            configSvc.config.findPreFetchCmd(id).isEmpty) {
            log.warn(s"Removing obsolete activeAlert data: $idlbl")
            m.remove(idlbl)
          }
        }
      }
    }
    cleanupMap(activeAlerts)
    cleanupMap(activeAlertsLastTs)
  }

  override def muteAll(): Unit = myIsMuted = true

  override def isMuted: Boolean = myIsMuted

  override def unmuteAll(): Unit = myIsMuted = false
}
