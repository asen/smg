package com.smule.smg.monitor

import play.api.libs.json.JsValue

import scala.concurrent.Future

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

