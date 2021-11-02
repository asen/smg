package com.smule.smgplugins.auth

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGLoggerApi

class SMGAuthPluginConfParser(pluginId: String, cfSvc: SMGConfigService, log: SMGLoggerApi) {

  private def parseConf(): SMGAuthPluginConf = {
    SMGAuthPluginConf.fromConfigGlobals(cfSvc.config.globals)
  }

  private var myConf : SMGAuthPluginConf = try {
    parseConf()
  } catch { case t: Throwable =>
    log.ex(t, s"SMGAuthPluginConfParser.init - unexpected exception (assuming empty conf): ${t.getMessage}")
    SMGAuthPluginConf.default
  }

  def reload(): Unit = {
    try {
      myConf = parseConf()
    } catch { case t: Throwable =>
      log.ex(t, s"SMGAuthPluginConfParser.reload - unexpected exception (config NOT reloaded): ${t.getMessage}")
    }
  }

  def conf: SMGAuthPluginConf = myConf
}
