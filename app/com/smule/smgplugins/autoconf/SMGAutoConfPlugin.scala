package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigService
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smg.rrd.SMGRrd

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class SMGAutoConfPlugin (
                          val pluginId: String,
                          val interval: Int,
                          val pluginConfFile: String,
                          val smgConfSvc: SMGConfigService
                        ) extends SMGPlugin{
  private val log = new SMGPluginLogger(pluginId)
  private val confParser = new SMGAutoConfPluginConfParser(pluginId, pluginConfFile, log)
  private val templateProcessor = new SMGTemplateProcessor(log, confParser.conf.preventTemplateReload)
  private val myEc: ExecutionContext =
    smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.plugins-shared")

  private val confFilesLastUpdatedTs = TrieMap[String, Int]()

  override def reloadConf(): Unit = {
    log.debug("SMGAutoConfPlugin.reloadConf")
    confParser.reload()
    cleanupConfFilesLastUpdated(confParser.conf)
  }

//  override def showInMenu: Boolean =
  override def run(): Unit = {
    log.debug("SMGAutoConfPlugin.run")
    if (!checkAndSetRunning){
      log.error("SMGAutoConfPlugin.run - overlapping runs detected - aborting")
      return
    }
    log.info("SMGAutoConfPlugin.run - starting")
    val pluginConf = confParser.conf
    Future {
      try {
        log.debug("SMGAutoConfPlugin.run - processing in async thread")
        val targetProcessor = new SMGAutoTargetProcessor(pluginConf, templateProcessor, smgConfSvc, log)
        if (processTargets(pluginConf, targetProcessor)){
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

  private def cleanupConfFilesLastUpdated(newConf: SMGAutoConfPluginConf): Unit = {
    val newConfFiles = newConf.targets.map(_.confOutput).toSet
    val oldConfFiles = confFilesLastUpdatedTs.keys.toSeq
    oldConfFiles.foreach { cf =>
      if (!newConfFiles.contains(cf)){
        confFilesLastUpdatedTs.remove(cf)
      }
    }
  }

  private def needUpdate(targetConf: SMGAutoTargetConf, tssNow: Int, rand: Random): Boolean = {
    val lastUpdated = confFilesLastUpdatedTs.get(targetConf.confOutput)
    val timeSinceLastUpdate = tssNow - lastUpdated.getOrElse(tssNow)
    if (timeSinceLastUpdate > 0 && targetConf.regenDelay.isDefined) {
      val regenDelay = targetConf.regenDelay.get
      if (regenDelay >= -1) {
        regenDelay <= timeSinceLastUpdate
      } else {
        val randomRegenDelay = rand.nextInt(regenDelay.abs - 1)
        randomRegenDelay <= timeSinceLastUpdate
      }
    } else true
  }

  // for each target:
  //   1. run command
  //   2. parse output
  //   3. generate yaml from output
  // return true if anything changed (and conf needs reload)
  def processTargets(pluginConf: SMGAutoConfPluginConf, tp: SMGAutoTargetProcessor): Boolean = {
    // TODO for now processing one target at a time to save the cpu for normal polling
    // may consider async processing (via an Actor in the future)
    val tssNow = SMGRrd.tssNow
    val rand = new Random()
    var needReload: Boolean = false
    pluginConf.targets.foreach { targetConf =>
      if (needUpdate(targetConf, tssNow, rand)) {
        log.debug(s"SMGAutoConfPlugin: Processing target conf: ${targetConf.confOutput}: conf=$targetConf")
        val targetReload = tp.processTarget(targetConf)
        if (targetReload) {
          log.info(s"SMGAutoConfPlugin: Done processing target conf: ${targetConf.uid}: " +
            s"targetReload=$targetReload needReload=$needReload")
          needReload = true
        }
        //only update last updated on success
        confFilesLastUpdatedTs.put(targetConf.confOutput, tssNow)
      } else {
        log.debug(s"SMGAutoConfPlugin: Skipping target conf: ${targetConf.confOutput} due to " +
          s"regenDelay=${targetConf.regenDelay.getOrElse(0)}: conf=$targetConf")
      }
    }
    if (pluginConf.confOutputDir.isDefined && pluginConf.confOutputDirOwned){
      if (tp.cleanupOwnedDir(pluginConf.confOutputDir.get,
        pluginConf.targets.map(_.confOutputFile(pluginConf.confOutputDir))))
        needReload = true
    }
    needReload
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
