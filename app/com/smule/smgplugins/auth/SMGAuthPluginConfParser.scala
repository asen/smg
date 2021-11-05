package com.smule.smgplugins.auth

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGLoggerApi

class SMGAuthPluginConfParser(pluginId: String, cfSvc: SMGConfigService, log: SMGLoggerApi) {

  private def parseConf(): SMGAuthPluginConf = {
    SMGAuthPluginConf.fromConfigGlobals(cfSvc.config.globals)
  }

  // this will be updated by the reload call invoked by the plugin's onConfigReloaded callback
  // this is synchronous on startup (SMG will not start until it completes) and then async afterwards
  private var myConf : SMGAuthPluginConf = SMGAuthPluginConf.default

  def reload(): Unit = {
    try {
      myConf = parseConf()
    } catch { case t: Throwable =>
      log.ex(t, s"SMGAuthPluginConfParser.reload - unexpected exception (config NOT reloaded): ${t.getMessage}")
    }
  }

  def conf: SMGAuthPluginConf = myConf
}
