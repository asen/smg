package com.smule.smgplugins.jmx

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{CommandResult, ParentCommandData}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}

import scala.concurrent.{ExecutionContext, Future}

/**
  * TODO
  */

class SMGJmxPlugin (val pluginId: String,
                    val interval: Int,
                    val pluginConfFile: String,
                    val smgConfSvc: SMGConfigService
                   ) extends SMGPlugin {

  override val showInMenu: Boolean = false

  private val myEc: ExecutionContext =
    smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.plugins-shared")


  override val autoRefresh: Boolean = false

  val log = new SMGPluginLogger(pluginId)

  private val confParser = new SMGJmxConfigParser(pluginId, pluginConfFile, smgConfSvc, log)

  private var rmiSocketTimeoutMillis = 0L // set to 0 to force initialization

  private val jmxClient = new SMGJmxClient(log)

  private val commandRunner = new SMGJmxCommands(log, jmxClient)

  override def runPluginFetchCommand(cmd: String, timeoutSec: Int,
                                     parentData: Option[ParentCommandData]): CommandResult =
    commandRunner.runPluginFetchCommand(cmd, timeoutSec, parentData)

  override def run(): Unit = {
    if (!checkAndSetRunning){
      log.error("SMGJmxPlugin.run - overlapping runs detected - aborting")
      return
    }
    Future {
      try {
        jmxClient.cleanupObsoleteConnections(interval * 3) // TODO should be configurable
      } catch {
        case t: Throwable => {
          log.ex(t, "SMGJmxPlugin.run: Unexpected exception from cleanupObsoleteConnections: " + t)
        }
      } finally {
        finished()
      }
    }(myEc)
  }

  override def reloadConf(): Unit = {
    confParser.reload()
    val conf = confParser.conf
    if (conf.timeoutMs != rmiSocketTimeoutMillis) { // only do this on change
      log.info(s"SMGJmxPlugin.reloadConf: updating RMI timeout to ${conf.timeoutMs}")
      SMGJmxConnection.setRMITimeout(conf.timeoutMs.toInt)
      rmiSocketTimeoutMillis = conf.timeoutMs
    }
  }
}

