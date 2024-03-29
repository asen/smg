package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigService
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smg.rrd.SMGRrd

import java.util.Date
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import scala.xml.{Elem, NodeBuffer}

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

  private var targetStatuses: (List[SMGAutoTargetStatus], List[SMGAutoTargetStatus]) = confParser.conf.targets.map { t =>
    SMGAutoTargetStatus(t, Some("unknown"))
  }.toList.partition(!_.isOk)

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
        val failedTargetOutputs = targetProcessor.getFailedTargetsByOutputFile
        targetStatuses = pluginConf.targets.map { aConf =>
          SMGAutoTargetStatus(aConf, failedTargetOutputs.get(aConf.confOutput))
        }.toList.partition(!_.isOk)
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
          log.info(s"SMGAutoConfPlugin: Done processing target conf: ${targetConf.confOutput}: " +
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

  private def confOverview(conf: SMGAutoConfPluginConf): NodeBuffer = {
    <h3>Plugin {pluginId}: Configuration</h3>
      <div>
        confOutputDir={confParser.conf.confOutputDir.getOrElse("None")}
        (owned={confParser.conf.confOutputDirOwned.toString})
        <br/>
        templateDirs={confParser.conf.templateDirs.mkString(", ")}
        (prevent_reload={confParser.conf.preventTemplateReload.toString})
        <br/>
      </div>
  }

  private def targetsList(tagets: Seq[SMGAutoTargetStatus]): Elem = {
    <ol>
      {tagets.map { as =>
      <li>
        <h5>{as.aConf.confOutput}:
          Last updated: {confFilesLastUpdatedTs.get(as.aConf.confOutput).map(n => new Date(1000L * n).toString).getOrElse("N/A")}
          Status: { as.errorStatus.getOrElse("OK") }</h5>
        <p>{as.aConf.inspect}</p>
      </li>
      }}
    </ol>
  }

  override def htmlContent(httpParams: Map[String,String]): String = {
    <div>
      { confOverview(confParser.conf)}
      { confParser.conf.failedTargets.headOption.map { _ =>
      <div>
        <h4>Targets with config errors:</h4>
        { targetsList(confParser.conf.failedTargets) }
      </div>
    }.getOrElse {
         <h4>No config errors detected</h4>
        }}
    </div>
      <div>
        { targetStatuses._1.headOption.map { _ =>
          <div>
            <h4>Targets with run-time/compile errors:</h4>
            {targetsList(targetStatuses._1) }
          </div>
        }.getOrElse {
          <h4>No run-time/compile errors detected</h4>
        }}
      </div>
      <div>
        { targetStatuses._2.headOption.map { _ =>
          <div>
            <h4>Configured template targets:</h4>
            { targetsList(targetStatuses._2) }
          </div>
        }.getOrElse {
          <h4>No targets detected</h4>
        }
      }
      </div>
  }.mkString
}
