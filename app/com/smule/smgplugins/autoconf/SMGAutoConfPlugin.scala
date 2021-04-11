package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigService
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}

import scala.concurrent.{ExecutionContext, Future}

class SMGAutoConfPlugin (
                          val pluginId: String,
                          val interval: Int,
                          val pluginConfFile: String,
                          val smgConfSvc: SMGConfigService
                        ) extends SMGPlugin{
  private val log = new SMGPluginLogger(pluginId)
  private val confParser = new SMGAutoConfPluginConfParser(pluginId, pluginConfFile, log)
  private val templateProcessor = new SMGTemplateProcessor()
  private val myEc: ExecutionContext =
    smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.plugins-shared")

  override def reloadConf(): Unit = {
    log.debug("SMGAutoConfPlugin.reloadConf")
    confParser.reload()
  }

//  override def showInMenu: Boolean =
  override def run(): Unit = {
    log.debug("SMGAutoConfPlugin.run")
    if (!checkAndSetRunning){
      log.error("SMGAutoConfPlugin.run - overlapping runs detected - aborting")
      return
    }
    log.info("SMGAutoConfPlugin.run - starting")
    Future {
      try {
        log.debug("SMGAutoConfPlugin.run - processing in async thread")
        val targetProcessor = new SMGAutoTargetProcessor(confParser.conf, templateProcessor, smgConfSvc, log)
        if (targetProcessor.run()){
          log.info("SMGAutoConfPlugin.run - reloading SMG conf due to changed configs")
          smgConfSvc.reloadLocal()
          smg.remotes.notifyMasters()
        }
        log.debug("SMGAutoConfPlugin.run - done processing in async thread")
      } catch { case t: Throwable =>
        log.ex(t, s"SMGAutoConfPlugin.run - unexpected error: ${t.getMessage}")
      } finally {
        log.info("SMGAutoConfPlugin.run - finished")
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
