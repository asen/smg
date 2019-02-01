package com.smule.smg.monitor

import com.smule.smg.{SMGConfigService, SMGPreFetchCmd}

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

