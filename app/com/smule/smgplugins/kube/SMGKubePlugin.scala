package com.smule.smgplugins.kube

import com.smule.smg.config.{SMGConfIndex, SMGConfigService}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}

import scala.concurrent.{ExecutionContext, Future}


class SMGKubePlugin(
                     val pluginId: String,
                     val interval: Int,
                     val pluginConfFile: String,
                     val smgConfSvc: SMGConfigService
                   ) extends SMGPlugin {

  private val log = new SMGPluginLogger(pluginId)
  private val confParser = new SMGKubePluginConfParser(pluginId, pluginConfFile, log)
  val targetProcessor = new SMGKubeClusterProcessor(confParser, smgConfSvc, log)

  private val myEc: ExecutionContext =
    smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.plugins-shared")
  
  override def reloadConf(): Unit = {
    log.debug("SMGKubePlugin.reloadConf")
    confParser.reload()
  }

  override def indexes: Seq[SMGConfIndex] = targetProcessor.indexes

  private def reloadScrapeConf(): Unit = {
    val scrapePlugin = smgConfSvc.plugins.find(_.pluginId == "scrape")
    if (scrapePlugin.isEmpty){
      log.error("Could not find the scrape plugin instance, this is unlikely to work as expected. " +
        "Trying ConfigService reload")
      smgConfSvc.reload()
    } else {
      try {
        scrapePlugin.get.reloadConf()
      } catch { case t: Throwable =>
        log.ex(t, s"Unexpected error from scrapePlugin.reload: ${t.getMessage}")
      }
    }
  }

  override def run(): Unit = {
    if (!checkAndSetRunning){
      log.error("SMGKubePlugin.run - overlapping runs detected - aborting")
      return
    }
    log.info("SMGKubePlugin.run - starting")
    Future {
      try {
        log.debug("SMGKubePlugin.run - processing in async thread")
        if (targetProcessor.run()){
          log.info("SMGKubePlugin.run - reloading Scrape conf due to changed configs")
          reloadScrapeConf()
        }
        log.debug("SMGKubePlugin.run - done processing in async thread")
      } catch { case t: Throwable =>
        log.ex(t, s"SMGKubePlugin.run - unexpected error: ${t.getMessage}")
      } finally {
        log.info("SMGKubePlugin.run - finished")
        finished()
      }
    }(myEc)
    log.info("SMGKubePlugin - done")
  }

  override def htmlContent(httpParams: Map[String,String]): String = {
    <h3>Plugin {pluginId}: Configuration</h3>
      <h4>Conf</h4>
      <ul>
        <li>scrapeTargetsD={confParser.conf.scrapeTargetsD}</li>
      </ul>
      <h4>Configured clusters</h4>
      <ul>
        {confParser.conf.clusterConfs.map { cconf =>
        <li>
          <h5>{cconf.inspect}</h5>
        </li>
      }}
      </ul>

  }.mkString
}
