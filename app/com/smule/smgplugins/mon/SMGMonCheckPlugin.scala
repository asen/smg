package com.smule.smgplugins.mon

import com.smule.smg._
import com.smule.smgplugins.mon.anom.AnomCheck
import com.smule.smgplugins.mon.pop.POPCheck

class SMGMonCheckPlugin(val pluginId: String,
                        val interval: Int,
                        val pluginConfFile: String,
                        val smgConfSvc: SMGConfigService
                       ) extends SMGPlugin {

  val log = new SMGPluginLogger(pluginId)

  val anomCheck = new AnomCheck("anom", log)

  val popCheck = new POPCheck("pop", log, smgConfSvc)

  override def valueChecks: Map[String, SMGMonCheck] = Map(
    anomCheck.ckId -> anomCheck,
    popCheck.ckId -> popCheck
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
    popCheck.cleanupObsoleteStates(pluginId)
  }


  override def onShutdown(): Unit = {
    anomCheck.saveStateToDisk(stateDir)
  }
}
