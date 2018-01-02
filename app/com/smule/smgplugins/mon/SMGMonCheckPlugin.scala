package com.smule.smgplugins.mon

import com.smule.smg._
import com.smule.smgplugins.mon.anom.AnomCheck

class SMGMonCheckPlugin(val pluginId: String,
                        val interval: Int,
                        val pluginConfFile: String,
                        val smgConfSvc: SMGConfigService
                       ) extends SMGPlugin {

  val log = new SMGPluginLogger(pluginId)

  val anomCheck = new AnomCheck("anom", log)

  override def valueChecks: Map[String, SMGMonCheck] = Map(
    anomCheck.ckId -> anomCheck
  )

  private var stateDir = "monstate"
  private var stateLoaded = false

  override def onConfigReloaded(): Unit = {
    stateDir = smgConfSvc.config.monStateDir
    if (!stateLoaded){
      anomCheck.loadStateFromDisk(stateDir)
      stateLoaded = true
    }
    anomCheck.cleanupObsoleteStates(smgConfSvc, pluginId)
  }


  override def onShutdown(): Unit = {
    anomCheck.saveStateToDisk(stateDir)
  }
}
