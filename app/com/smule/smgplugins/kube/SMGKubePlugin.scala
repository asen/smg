package com.smule.smgplugins.kube

import com.smule.smg.config.SMGConfigService
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}


class SMGKubePlugin(
                     val pluginId: String,
                     val interval: Int,
                     val pluginConfFile: String,
                     val smgConfSvc: SMGConfigService
                   ) extends SMGPlugin {

  private val log = new SMGPluginLogger(pluginId)
  private val kubeClient = new SMGKubeClient(log)

  override def run(): Unit = {
    log.info("SMGKubePlugin - running")
//    try {
//      kubeClient.listPods().foreach { s =>
//        log.info(s"POD: ${s}")
//      }
//    } catch { case t: Throwable =>
//      log.error(s"Unexpceted error from kubeClient: ${t}")
//    }
    log.info("SMGKubePlugin - done")
  }
}
