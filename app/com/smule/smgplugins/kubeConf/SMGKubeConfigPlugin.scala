package com.smule.smgplugins.kubeConf

import com.smule.smg.config.SMGConfigService
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}


class SMGKubeConfigPlugin(val pluginId: String,
                          val interval: Int,
                          val pluginConfFile: String,
                          val smgConfSvc: SMGConfigService
                         ) extends SMGPlugin {

  private val log = new SMGPluginLogger(pluginId)
  private val kubectlClient = new SMGKubeClient(log)

  override def run(): Unit = {
    log.info("SMGKubeConfigPlugin - running")
    try {
      kubectlClient.listPods().foreach { s =>
        log.info(s"POD: ${s}")
      }
    } catch { case t: Throwable =>
      log.error(s"Unexpceted error from kubectlClient: ${t}")
    }
    log.info("SMGKubeConfigPlugin - done")
  }
}
