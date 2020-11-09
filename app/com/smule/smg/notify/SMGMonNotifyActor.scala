package com.smule.smg.notify

import java.util.Date

import akka.actor.{Actor, ActorRef, Props}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGCmdException, SMGLogger, SendToSelfActor}
import com.smule.smg.monitor.SMGMonState
import com.smule.smg.notify.SMGMonNotifyActor._
import com.smule.smg.rrd.SMGRrd

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class SMGMonNotifyActor(configSvc: SMGConfigService, state: NotifyActorState, ec: ExecutionContext) extends Actor {

  // Internal messages (via sendToSelf)
  // helper to hold details about asynchronously run notification cmd
  private case class InProgressCommand(
                                        ncmd:  SMGMonNotifyCmd,
                                        severity: SMGMonNotifySeverity.Value,
                                        alertKey: String
                                      )
  // trait to customize behavior on async command result
  // complete() is executed in the actor thread
  private trait PostCommandMsgHandler {
    def complete(ipc: InProgressCommand, success: Boolean)
  }
  // messag sent to self on command completions
  private case class RunStateCommandCompleteMsg(
                                                 ipc: InProgressCommand,
                                                 success: Boolean,
                                                 onComplete: PostCommandMsgHandler
                                               )
  // PostCommandMsgHandler ignoring result (only logging success!=false cases
  private case class IgnoreResultHandler() extends PostCommandMsgHandler {
    override def complete(ipc: InProgressCommand, success: Boolean): Unit = {
      if (!success)
        log.warn(s"SMGMonNotifyActor ignoring failed result from $ipc")
    }
  }
  // PostCommandMsgHandler used to update activeAlertsLastTs on successful alert command
  private case class AlertResultHandler() extends PostCommandMsgHandler {
    override def complete(ipc: InProgressCommand, success: Boolean): Unit = {
      log.debug(s"SMGMonNotifyActor received result from $ipc success=$success")
      if (success ) {
        val cmdsMap = state.activeAlertsLastTs.getOrElseUpdate(ipc.alertKey, {
          TrieMap[String, Int]()
        })
        cmdsMap(ipc.ncmd.id) = SMGRrd.tssNow
      } else
        log.error(s"SMGMonNotifyActor.AlertResultHandler: failed $ipc (subsequent calls will result in retry)")
    }
  }
  
  private val log = SMGLogger
  private val sendToSelfActor = context.actorOf(SendToSelfActor.props(self))

  private val MAX_CONDENSED_SUBJECTS = 100

  // Don't throttle acknowledgements and recoveries
  private val dontThrottleSeverities = Set(SMGMonNotifySeverity.RECOVERY, SMGMonNotifySeverity.ACKNOWLEDGEMENT)

  // keep track of currently running async commands to avoid double-running
  private val inProgress = mutable.Set[InProgressCommand]()

  private def throttleInterval: Int = configSvc.config.globals.getOrElse("$notify-throttle-interval", "3600").toInt
  private def throttleMaxCount: Int = configSvc.config.globals.getOrElse("$notify-throttle-count", Int.MaxValue.toString).toInt
  private def remoteSubjStr: String = configSvc.config.notifyRemoteId.map(s => s"($s)").getOrElse("")

  private def realRunStateCommandSync(
                                   ncmd:  SMGMonNotifyCmd,
                                   severity: SMGMonNotifySeverity.Value,
                                   alertKey:String,
                                   subj: String,
                                   body: String
                                 ): Boolean = {
    if (state.isMuted) {
      log.warn(s"SMGMonNotifyActor.notify: Muted state prevents notifications for ${alertKey} with subject $subj")
      return false
    }
    try {
      ncmd.alert(severity, alertKey, subj, body)
      log.info(s"SMGMonNotifyActor.notify: notified ${ncmd.id} for $subj")
      true
    } catch {
      case cex: SMGCmdException => {
        log.error(s"SMGMonNotifyActor.notify: notification failed $ncmd subj=$subj body=$body (${cex.getMessage})")
        false
      }
      case t: Throwable => {
        log.ex(t, s"SMGMonNotifyActor.notify: unexpected error: $ncmd subj=$subj body=$body (${t.getMessage})")
        false
      }
    }
  }

  private def runStateCommandAsync(
                                    ncmd:  SMGMonNotifyCmd,
                                    severity: SMGMonNotifySeverity.Value,
                                    alertKey:String,
                                    subj: String,
                                    body: String,
                                    onCompleteHandler: PostCommandMsgHandler
                                  ): Unit = {
    val ipc = InProgressCommand(ncmd, severity, alertKey)
    if (inProgress.contains(ipc)){
      log.error(s"SMGMonNotifyActor: Command is already in progress (aborting) $ipc")
      return
    }
    inProgress.add(ipc)
    Future {
      var success = false
      try {
        success = realRunStateCommandSync(ncmd, severity, alertKey, subj, body)
      } finally {
        sendToSelfActor ! RunStateCommandCompleteMsg(ipc, success, onCompleteHandler)
      }
    }(ec)
  }

  private def onStateCommandComplete(ipc: InProgressCommand, success: Boolean,
                                     onCompleteHandler: PostCommandMsgHandler): Unit = {
    try {
      onCompleteHandler.complete(ipc, success)
    } finally {
      inProgress.remove(ipc)
    }
  }

  private def sendThrottledMsg(ncmd: SMGMonNotifyCmd, cnt: Int, maxCnt: Int, throttledUntil: Int): Unit = {
    val msgSubj = s"THROTTLED $remoteSubjStr message rate ($cnt) reached $maxCnt msgs/$throttleInterval sec"
    if (state.isMuted) {
      log.warn(s"SMGMonNotifyActor.notify: Muted state prevents sending of throttle messages with subj: $msgSubj")
      return
    }
    val msgBody = s"$msgSubj: \n\n No more alert notifications will be sent " +
      s"until ${new Date(1000L * throttledUntil).toString}.\n\n" +
      s"Check ${configSvc.config.notifyBaseUrl}/monitor#rt_${configSvc.config.notifyRemoteId.getOrElse("")}\n\n"
    runStateCommandAsync(ncmd, SMGMonNotifySeverity.THROTTLED,
      SMGMonNotifySeverity.THROTTLED.toString, msgSubj, msgBody, IgnoreResultHandler())
  }

  private def sendUnthrottledMsg(ncmd: SMGMonNotifyCmd, msgsToCondense: Seq[SMGMonThrottledMsgData]): Unit = {
    // send condensed states as a single msg, async
    if (msgsToCondense.nonEmpty) {
      val msgSubj = s"UNTHROTTLED $remoteSubjStr - ${msgsToCondense.size} messages supressed during throttle"
      val condensedSubjects = msgsToCondense.map(_.subjStr).take(MAX_CONDENSED_SUBJECTS)
      val msgBody = s"$msgSubj. Some example subjects (displaying ${condensedSubjects.size}/${msgsToCondense.size}):\n\n" +
        condensedSubjects.mkString("\n") +
        s"\n\nURL: ${configSvc.config.notifyBaseUrl}/monitor#rt_${configSvc.config.notifyRemoteId.getOrElse("")}"
      runStateCommandAsync(ncmd, SMGMonNotifySeverity.UNTHROTTLED,
        SMGMonNotifySeverity.UNTHROTTLED.toString, msgSubj, msgBody, IgnoreResultHandler())
    }
  }


  private def flushThrottledMessages(ncmd: SMGMonNotifyCmd,
                                     curState: SMGMonThrottledRecipientState,
                                     nowHourTs: Int): Unit= {
    val msgsToCondense = curState.throttledMsgs.toList
    curState.throttledMsgs.clear()
    curState.throttledIsSent = false
    curState.currentThrottleCnt = 1
    curState.currentThrottleTs = nowHourTs
    sendUnthrottledMsg(ncmd, msgsToCondense)
  }

  private def updateThrottleCounters(severity: SMGMonNotifySeverity.Value,
                                     akey: String,
                                     subj: String,
                                     ncmd: SMGMonNotifyCmd): Boolean = {
    if (dontThrottleSeverities.contains(severity)) {
      true
    } else {
      val curState = state.throttles.getOrElseUpdate(ncmd.id, { new SMGMonThrottledRecipientState() })
      val intvl = throttleInterval
      val nowHourTs = (SMGRrd.tssNow / intvl) * intvl // rounded to beginning of throttle interval
      if (nowHourTs == curState.currentThrottleTs) {
        curState.currentThrottleCnt += 1
        if (curState.currentThrottleCnt >= throttleMaxCount) {
          curState.throttledMsgs += SMGMonThrottledMsgData(
            severity,
            akey, subj)
          if (!curState.throttledIsSent) {
            sendThrottledMsg(ncmd, curState.currentThrottleCnt, throttleMaxCount, nowHourTs + intvl)
            curState.throttledIsSent = true
          }
          false
        } else
          true
      } else {
        // new throttle period
        flushThrottledMessages(ncmd, curState, nowHourTs)
        true
      }
    }
  }

  private def processStateCommand(monState: SMGMonState,  ncmd:  SMGMonNotifyCmd,
                                  isRepeat: Boolean, isImprovement: Boolean,
                                  onCompleteHandler: PostCommandMsgHandler): Unit = {
    val improvedStr = if (isImprovement) "(improved) " else ""
    val subj = improvedStr + monState.notifySubject(configSvc.config.notifyBaseUrl, configSvc.config.notifyRemoteId, isRepeat)
    val severity = SMGMonNotifySeverity.fromStateValue(monState.currentStateVal)
    if (updateThrottleCounters(severity, monState.alertKey, subj, ncmd)){
      val body = monState.notifyBody(configSvc.config.notifyBaseUrl, configSvc.config.notifyRemoteId)
      runStateCommandAsync(ncmd, severity, monState.alertKey, subj, body, onCompleteHandler)
    } else {
      log.warn(s"SMGMonNotifyActor.notify: Throttling prevents message to ${ncmd.id} with subject ${subj}")
    }
  }

  private def processAlertNotifyMsg(msg: SendAlertNotifyMsg): Unit = {
    log.debug(s"SMGMonNotifyActor.processAlertNotifyMsg: ${msg.monState.alertKey} ${msg}")
    msg.ncmds.foreach { ncmd =>
      processStateCommand(msg.monState, ncmd, msg.isRepeat, msg.isImprovement, AlertResultHandler())
    }
  }

  private def checkAndResendAlertMessages(monState: SMGMonState, backOffSeconds: Int): Unit = {
    log.debug(s"SMGMonNotifyActor.checkAndResendAlertMessages: ${monState.alertKey} backOffSeconds=${backOffSeconds}")
    val akey = monState.alertKey
    val tsNow = SMGRrd.tssNow
    val cmdsMap = state.activeAlertsLastTs.getOrElseUpdate(akey, {TrieMap()})
    cmdsMap.keys.toSeq.foreach { ncmdId =>
      val ncmdOpt = configSvc.config.notifyCommands.get(ncmdId)
      if (ncmdOpt.isEmpty) {
        log.warn(s"SMGMonNotifyActor: Removing activeAlertsLastTs entry for no longer existing command: $ncmdId")
        cmdsMap.remove(ncmdId)
      } else {
        val tsLast = cmdsMap.get(ncmdId)
        if (tsNow - tsLast.getOrElse(0) >= backOffSeconds) {
          processStateCommand(monState, ncmdOpt.get, isRepeat = true, isImprovement = false, AlertResultHandler())
        }
      }
    }
  }

  private def tick(): Unit = {
    val intvl = throttleInterval
    val nowHourTs = (SMGRrd.tssNow / intvl) * intvl // rounded to beginning of throttle interval
    state.throttles.toSeq.foreach { case (ncmdId, tstate) =>
      if (nowHourTs != tstate.currentThrottleTs) {
        // new throttle period
        val ncmdOpt = configSvc.config.notifyCommands.get(ncmdId)
        if (ncmdOpt.isEmpty){
          log.warn(s"SMGMonNotifyActor: Removing throttles entry for no longer existing command: $ncmdId")
          state.throttles.remove(ncmdId)
        } else {
          log.debug(s"SMGMonNotifyActor.tick: new throttle interval " +
            s"($throttleMaxCount/$throttleInterval) throttles.isEmpty=${state.throttles.isEmpty}")
          flushThrottledMessages(ncmdOpt.get, tstate, nowHourTs)
        }
      }
    }
  }

  private def configReloaded(): Unit = {
    // TODO too dangerous?
    log.debug(s"SMGMonNotifyActor.configReloaded")
    state.activeAlertsLastTs.toList.foreach { case (ak, cm) =>
      val ids = ak.split(",").toList
      ids.foreach { idlbl =>
        val id = idlbl.split(":")(0)
        if (!id.startsWith(SMGMonState.MON_STATE_GLOBAL_PX) &&
          !configSvc.config.updateObjectsById.contains(id) &&
          configSvc.config.findPreFetchCmd(id).isEmpty) {
          log.warn(s"SMGMonNotifyActor.configReloaded: Removing obsolete activeAlert data: $ak ($idlbl)")
          state.activeAlertsLastTs.remove(ak)
        }
      }
    }
  }

  private def sendRecoveryMessages(monState: SMGMonState): Unit = {
    log.debug(s"SMGMonNotifyActor.sendRecoveryMessages: ${monState.alertKey}")
    val akey = monState.alertKey
    val cmdsMap = state.activeAlertsLastTs.remove(akey)
    if (cmdsMap.isDefined) {
      cmdsMap.get.toSeq.foreach { case (ncmdId, ts) =>
        val ncmdOpt = configSvc.config.notifyCommands.get(ncmdId)
        if (ncmdOpt.isEmpty){
          log.warn(s"SMGMonNotifyActor (recovery): Removing active alert entry for no longer existing command: $ncmdId")
          cmdsMap.get.remove(ncmdId)
        } else {
          // XXX setting isImprovement to false to avoid somewhat redundant "(improved) RECOVERY" strings in subj
          // also ignoring result because we already removed the activeAlertsLastTs entry
          processStateCommand(monState, ncmdOpt.get, isRepeat = false, isImprovement = false, IgnoreResultHandler())
        }
      }
    }
//    else
//      log.warn(s"SMGMonNotifyActor.sendRecoveryMessages: no activeAlertsLastTs commands map for $akey")
  }

  private def sendAcknowledgementMessages(monState: SMGMonState): Unit = {
    log.debug(s"SMGMonNotifyActor.sendAcknowledgementMessages: ${monState.alertKey}")
    val akey = monState.alertKey
    val cmdsMap = state.activeAlertsLastTs.get(akey)
    if (cmdsMap.isDefined) {
      val subj = "ACKNOWLEDGEMENT: " + monState.notifySubject(configSvc.config.notifyBaseUrl, configSvc.config.notifyRemoteId, isRepeat = false)
      val body = "ACKNOWLEDGEMENT: " + monState.notifyBody(configSvc.config.notifyBaseUrl, configSvc.config.notifyRemoteId)
      cmdsMap.get.toSeq.foreach { case (ncmdId, ts) =>
        val ncmdOpt = configSvc.config.notifyCommands.get(ncmdId)
        if (ncmdOpt.isEmpty){
          log.warn(s"SMGMonNotifyActor (acknowledgement): Removing active alert entry for no longer existing command: $ncmdId")
          cmdsMap.get.remove(ncmdId)
        } else {
          runStateCommandAsync(ncmdOpt.get, SMGMonNotifySeverity.ACKNOWLEDGEMENT,
            monState.alertKey, subj, body, IgnoreResultHandler())
        }
      }
    } else {
      log.warn(s"SMGMonNotifyActor.sendAcknowledgementMessages: no activeAlertsLastTs commands map for $akey")
    }
  }

  override def receive: Receive = {
    case anm: SendAlertNotifyMsg => processAlertNotifyMsg(anm)
    case crm: CheckAndResendAlertNotifyMsg => checkAndResendAlertMessages(crm.monState, crm.backOffSeconds)
    case ccm: RunStateCommandCompleteMsg => onStateCommandComplete(ccm.ipc, ccm.success, ccm.onComplete)
    case SendRecoveryMessages(monState: SMGMonState) => sendRecoveryMessages(monState)
    case SendAcknowledgementMessages(monState: SMGMonState) => sendAcknowledgementMessages(monState)
    case NotifyTickMsg() => tick()
    case ConfigReloadedMsg() => configReloaded()
    case x => log.error(s"SMGMonNotifyActor: unexpected message: $x")
  }
}

object SMGMonNotifyActor {

  // Actor messages sent by NotifySvc
  // every tick
  case class NotifyTickMsg()
  //on config reloaded
  case class ConfigReloadedMsg()
  // send alert msg (on state change)
  case class SendAlertNotifyMsg(monState: SMGMonState, ncmds:  Seq[SMGMonNotifyCmd],
                                isRepeat: Boolean, isImprovement: Boolean)
  // check and re-send alert msgs if backoff has expired
  case class CheckAndResendAlertNotifyMsg(monState: SMGMonState, backOffSeconds: Int)
  // send recovery msg
  case class SendRecoveryMessages(monState: SMGMonState)
  // send acknowledgement msg
  case class SendAcknowledgementMessages(monState: SMGMonState)

  // props to help actor creation
  def props(configSvc: SMGConfigService,
            state: NotifyActorState,
            ec: ExecutionContext): Props = Props(new SMGMonNotifyActor(configSvc, state, ec))

  // "public" actor API
  def sendAlertMessages(aref: ActorRef, monState: SMGMonState,  ncmds:  Seq[SMGMonNotifyCmd],
                        isImprovement: Boolean): Unit = {
    aref ! SendAlertNotifyMsg(monState, ncmds, isRepeat = false, isImprovement = isImprovement)
  }

  def checkAndResendAlertMessages(aref: ActorRef, monState: SMGMonState, backOffSeconds: Int): Unit = {
    aref ! CheckAndResendAlertNotifyMsg(monState, backOffSeconds)
  }

  def sendRecoveryMessages(aref: ActorRef, monState: SMGMonState): Unit = {
    aref ! SendRecoveryMessages(monState)
  }

  def sendAcknowledgementMessages(aref: ActorRef, monState: SMGMonState): Unit = {
    aref ! SendAcknowledgementMessages(monState)
  }

  def tick(aref: ActorRef): Unit = {
    aref ! NotifyTickMsg()
  }

  def configReloaded(aref: ActorRef): Unit = {
    aref ! ConfigReloadedMsg()
  }
}
