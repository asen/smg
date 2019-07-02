package com.smule.smgplugins.mon

import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.monitor._
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smgplugins.mon.anom.AnomCheck
import com.smule.smgplugins.mon.ex.ExtendedCheck
import com.smule.smgplugins.mon.pop.POPCheck

class SMGMonCheckPlugin(val pluginId: String,
                        val interval: Int,
                        val pluginConfFile: String,
                        val smgConfSvc: SMGConfigService
                       ) extends SMGPlugin {

  override val showInMenu: Boolean = false

  val log = new SMGPluginLogger(pluginId)

  val anomCheck = new AnomCheck("anom", log, smgConfSvc)

  val popCheck = new POPCheck("pop", log, smgConfSvc)

  val exCheck = new ExtendedCheck("ex", log, smgConfSvc)

  override def valueChecks: Map[String, SMGMonCheck] = Map(
    anomCheck.ckId -> anomCheck,
    popCheck.ckId -> popCheck,
    exCheck.ckId -> exCheck
  )

  private var stateDir = "monstate"
  private var stateLoaded = false

  override def onConfigReloaded(): Unit = {
    stateDir = smgConfSvc.config.monStateDir
    if (!stateLoaded){
      // TODO need a different way to do this
      // anomCheck.loadStateFromDisk(stateDir)
      stateLoaded = true
    }
    anomCheck.cleanupObsoleteStates(smgConfSvc, pluginId)
    popCheck.cleanupObsoleteStates(pluginId)
    exCheck.cleanupObsoleteStates(pluginId)
  }


  override def onShutdown(): Unit = {
    // TODO need a different way to do this
    // anomCheck.saveStateToDisk(stateDir)
  }
}
