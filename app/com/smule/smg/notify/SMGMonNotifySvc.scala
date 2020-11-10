package com.smule.smg.notify

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGLogger
import com.smule.smg.monitor.{SMGMonAlertActive, SMGMonState, SMGState}
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsValue, Json}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/**
  * Created by asen on 8/31/16.
  */

@Singleton
class SMGMonNotifySvc @Inject() (
                                  configSvc: SMGConfigService
                                ) extends SMGMonNotifyApi {

//  // object to store throttled msg data until next unthrottled msg / flush
//  case class SMGMonThrottledMsgData(severity: SMGMonNotifySeverity.Value,
//                                    alertKey: String,
//                                    subjStr: String,
//                                    cmds: Seq[SMGMonNotifyCmd]
//                                   )

  private val log = SMGLogger

  private val ec: ExecutionContext = configSvc.executionContexts.monitorCtx
  private val actorState = new NotifyActorState()
  // XXX state is loaded from disk after that (triggered by monitor svc via deserializeState)
  private val notifyActor = configSvc.actorSystem.actorOf(SMGMonNotifyActor.props(configSvc, actorState, ec))

  override def sendAlertMessages(monState: SMGMonState,  ncmds:  Seq[SMGMonNotifyCmd],
                                 isImprovement: Boolean): Unit = {
    if (ncmds.nonEmpty)
      SMGMonNotifyActor.sendAlertMessages(notifyActor, monState, ncmds, isImprovement)
    else if (monState.currentStateVal > SMGState.ANOMALY)
      log.warn(s"SMGMonNotifySvc.sendAlertMessages: empty recipient list for ${monState.id} (${monState.currentStateVal})")
  }

  override def checkAndResendAlertMessages(monState: SMGMonState, backOffSeconds: Int): Unit = {
    SMGMonNotifyActor.checkAndResendAlertMessages(notifyActor, monState, backOffSeconds)
  }

  override def sendRecoveryMessages(monState: SMGMonState): Unit = {
    SMGMonNotifyActor.sendRecoveryMessages(notifyActor, monState)
  }

  override def sendAcknowledgementMessages(monState: SMGMonState): Unit = {
    SMGMonNotifyActor.sendAcknowledgementMessages(notifyActor, monState)
  }

  override def getActiveAlerts: Map[String,SMGMonAlertActive] = actorState.activeAlertsLastTs.toMap.map { t =>
    // TODO - change SMGMonAlertActive to contain a map of cmd -> ts (bs list of cmds + single ts)
    val ret = SMGMonAlertActive(
      t._1, t._2.keys.toList,
      if (t._2.values.isEmpty) None else Some(t._2.values.max) // using last success ts
    )
    (t._1, ret)
  }

  override def configReloaded(): Unit = {
    SMGMonNotifyActor.configReloaded(notifyActor)
  }

  def tick(): Unit = {
    SMGMonNotifyActor.tick(notifyActor)
  }

//  override def serializeStateV0(): JsValue = {
//    Json.toJson(Map(
//      "aa" -> Json.toJson(activeAlerts.toMap.map { t =>
//        val ak = t._1
//        val jsv = Json.toJson(t._2.map(_.id))
//        (ak, jsv)
//        }),
//      "aalt" -> Json.toJson(activeAlertsLastTs.toMap),
//      "ismtd" -> Json.toJson(myIsMuted)
//    ))
//  }

  private def deserializeStateV0(src: JsValue): Unit = {
    log.warn(s"SMGMonNotifySvc.deserializeState: deserializing v0 state")
    val activeAlerts = mutable.Map[String,Seq[SMGMonNotifyCmd]]()
    val activeAlertsLastTs = mutable.Map[String,Int]()
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
    val myIsMuted = (src \ "ismtd").asOpt[Boolean].getOrElse(false)
    actorState.isMuted = myIsMuted
    activeAlerts.foreach { case (akey, ncmds) =>
      val tsOpt = activeAlertsLastTs.get(akey)
      if (tsOpt.isDefined){
        val mm = actorState.activeAlertsLastTs.getOrElseUpdate(akey, {TrieMap()})
        ncmds.foreach { ncmd =>
          mm.put(ncmd.id, tsOpt.get)
        }
      }
    }
  }

  override def serializeState(): JsValue = {
    Json.toJson(Map(
      "v" -> Json.toJson(1),
      "aam" -> Json.toJson(actorState.activeAlertsLastTs.toMap.map { case (akey, mm) =>
        val m = Json.toJson(mm.toMap)
        (akey, m)
      }),
      "ismtd" -> Json.toJson(actorState.isMuted)
    ))
  }

  private def deserializeStateV1(src: JsValue): Unit = {
    (src \ "aam").as[Map[String,JsValue]].foreach { case (akey, jsv) =>
      val jsm = jsv.as[Map[String,Int]]
      val mm = actorState.activeAlertsLastTs.getOrElseUpdate(akey, { TrieMap() })
      jsm.foreach { case (ncmdId, ts) =>
        if (configSvc.config.notifyCommands.contains(ncmdId))
          mm.put(ncmdId, ts)
        else
          log.warn(s"SMGMonNotifySvc.deserializeStateV1: non-existing command id: $ncmdId")
      }
    }
    actorState.isMuted = (src \ "ismtd").asOpt[Boolean].getOrElse(false)
  }

  override def deserializeState(srcStr: String): Unit = {
    try {
      //activeAlerts.clear()
      val src = Json.parse(srcStr)
      val versOpt = (src \ "v").asOpt[Int]
      if (versOpt.isEmpty)
        deserializeStateV0(src)
      else
        deserializeStateV1(src)
      log.info(s"SMGMonNotifySvc.deserializeState: " +
        s"loaded activeALerts=${actorState.activeAlertsLastTs.size} isMuted=${actorState.isMuted}")
    } catch {
      case t: Throwable => log.ex(t, "Unexpected exception in SMGMonNotifySvc.deserializeState")
    }
  }

  //TODO - should modify theis in the actor?
  override def muteAll(): Unit = actorState.isMuted = true
  override def unmuteAll(): Unit = actorState.isMuted = false

  override def isMuted: Boolean = actorState.isMuted

}
