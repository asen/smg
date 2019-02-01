package com.smule.smg.monitor

import com.smule.smg.{SMGConfigService, SMGObjectUpdate}

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
