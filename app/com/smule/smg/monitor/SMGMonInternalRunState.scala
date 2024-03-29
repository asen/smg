package com.smule.smg.monitor

import com.smule.smg.config.SMGConfigService
import com.smule.smg.notify.{SMGMonNotifyApi, SMGMonNotifyCmd, SMGMonNotifySeverity}

class SMGMonInternalRunState(val interval: Int,
                             val pluginId: Option[String],
                             val configSvc: SMGConfigService,
                             val monLog: SMGMonitorLogApi,
                             val notifSvc: SMGMonNotifyApi) extends SMGMonInternalState {

  override val id: String = SMGMonInternalRunState.stateId(interval, pluginId)

  override def intervals: Seq[Int] = Seq(interval)
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

object SMGMonInternalRunState {
  def stateId(interval: Int, pluginId: Option[String]): String = "$interval_%04d".format(interval) +
    pluginId.map(s => s"-$s").getOrElse("")
}

