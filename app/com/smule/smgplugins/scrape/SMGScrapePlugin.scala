package com.smule.smgplugins.scrape

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{CommandResult, ParentCommandData}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}

import scala.concurrent.{ExecutionContext, Future}

// THis plugin knows how to parse scraped metrics data in prometheus scrape format
class SMGScrapePlugin (
                        val pluginId: String,
                        val interval: Int,
                        val pluginConfFile: String,
                        val smgConfSvc: SMGConfigService
                      ) extends SMGPlugin {

  private val log = new SMGPluginLogger(pluginId)
  private val confParser = new SMGScrapePluginConfParser(pluginId, pluginConfFile, log)
  private val myEc: ExecutionContext =
    smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.plugins-shared")

  private val commandRunner = new SMGScrapeCommands(log)

  override def reloadConf(): Unit = {
    log.debug("SMGScrapePlugin.reloadConf")
    confParser.reload()
  }

  override def runPluginFetchCommand(cmd: String, timeoutSec: Int,
                                     parentData: Option[ParentCommandData]): CommandResult =
    commandRunner.runPluginFetchCommand(cmd, timeoutSec, parentData)

  override def run(): Unit = {
    if (!checkAndSetRunning){
      log.error("SMGScrapePlugin.run - overlapping runs detected - aborting")
      return
    }
    log.info("SMGScrapePlugin.run - starting")
    Future {
      try {
        log.debug("SMGScrapePlugin.run - processing in async thread")
        val targetProcessor = new SMGScrapeTargetProcessor(confParser.conf, smgConfSvc, log)
        if (targetProcessor.run()){
          log.info("SMGScrapePlugin.run - reloading SMG conf due to changed configs")
          smgConfSvc.reloadLocal()
          smg.remotes.notifyMasters()
        }
        log.debug("SMGScrapePlugin.run - done processing in async thread")
      } catch { case t: Throwable =>
        log.ex(t, s"SMGScrapePlugin.run - unexpected error: ${t.getMessage}")
      } finally {
        log.info("SMGScrapePlugin.run - finished")
        finished()
      }
    }(myEc)
  }

  override def htmlContent(httpParams: Map[String,String]): String = {
    <h3>Plugin {pluginId}: Configuration</h3>
      <h4>Configured scrapes</h4>
      <ul>
        {confParser.conf.targets.map { aconf =>
          <li>
            <h5>{aconf.inspect}</h5>
          </li>
        }}
      </ul>

  }.mkString
}
