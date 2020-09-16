package com.smule.smgplugins.influxdb

import com.smule.smg.config.SMGConfigService
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}

import scala.concurrent.ExecutionContext

class SMGInfluxDbPlugin(val pluginId: String,
                        val interval: Int,
                        val pluginConfFile: String,
                        val smgConfSvc: SMGConfigService
                             ) extends SMGPlugin  {

  private val myEc: ExecutionContext = smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.plugins-shared")

  private val log = new SMGPluginLogger(pluginId)
  private val confParser = new SMGInfluxDbPluginConfParser(pluginId, pluginConfFile, log, smgConfSvc)

  private val dataReceiver = new SMGDataReceiver(confParser, smgConfSvc, log)

  smgConfSvc.registerDataFeedListener(dataReceiver)

  override def onConfigReloaded(): Unit = {
     confParser.reload()
  }

  override def run(): Unit = {
    dataReceiver.flush()
  }
}
