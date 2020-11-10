package com.smule.smg.notify

import com.smule.smg.monitor.{SMGMonAlertActive, SMGMonState}
import play.api.libs.json.JsValue

trait SMGMonNotifyApi {

  def sendAlertMessages(monState: SMGMonState, ncmds: Seq[SMGMonNotifyCmd], isImprovement: Boolean): Unit

  def checkAndResendAlertMessages(monState: SMGMonState, ncmds: Seq[SMGMonNotifyCmd], backOffSeconds: Int): Unit

  def sendRecoveryMessages(monState: SMGMonState): Unit

  def sendAcknowledgementMessages(monState: SMGMonState): Unit

  def getActiveAlerts: Map[String, SMGMonAlertActive]

  def serializeState(): JsValue

  def deserializeState(srcStr: String): Unit

  def configReloaded(): Unit

  def tick(): Unit

  def muteAll(): Unit

  def isMuted: Boolean

  def unmuteAll(): Unit

}
