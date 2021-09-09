package com.smule.smgplugins.kube

import com.smule.smg.config.{SMGConfIndex, SMGConfigService}
import com.smule.smg.core.{CommandResult, ParentCommandData, SMGPreFetchCmd}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}


class SMGKubePlugin(
                     val pluginId: String,
                     val interval: Int,
                     val pluginConfFile: String,
                     val smgConfSvc: SMGConfigService
                   ) extends SMGPlugin {

  private val log = new SMGPluginLogger(pluginId)
  private val confParser = new SMGKubePluginConfParser(pluginId, pluginConfFile, log)
  private val targetProcessor = new SMGKubeClusterProcessor(confParser, smgConfSvc, log)
  private val commandRunners = TrieMap[String,SMGKubeCommands]()
  private val myEc: ExecutionContext =
    smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.plugins-shared")
  
  override def reloadConf(): Unit = {
    log.debug("SMGKubePlugin.reloadConf")
    confParser.reload()
  }

  override def showInMenu: Boolean = confParser.conf.clusterConfs.nonEmpty

  override def indexes: Seq[SMGConfIndex] = targetProcessor.indexes

  override def preFetches: Map[String, SMGPreFetchCmd] = targetProcessor.preFetches.
    groupBy(_.id).map{ case (k,v) => (k,v.head)}

  private def reloadAnotherPluginConf(pluginId: String): Unit = {
    val plugin = smgConfSvc.plugins.find(_.pluginId == pluginId)
    if (plugin.isEmpty){
      log.error(s"Could not find the $pluginId plugin instance, this is unlikely to work as expected. " +
        "Trying ConfigService reload")
      smgConfSvc.reloadLocal()
    } else {
      try {
        plugin.get.reloadConf()
      } catch { case t: Throwable =>
        log.ex(t, s"Unexpected error from reloadAnothePluginConf($pluginId).reload: ${t.getMessage}")
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
        val runResult = targetProcessor.run()
        if (runResult.reloadAutoconf){
          log.info("SMGKubePlugin.run - reloading Autoconf conf due to changed configs")
          reloadAnotherPluginConf("autoconf")
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

  override def runPluginFetchCommand(cmd: String, timeoutSec: Int,
                                     parentData: Option[ParentCommandData]): CommandResult = {
    val arr = cmd.split("\\s+", 2)
    val runner = commandRunners.getOrElseUpdate(arr(0), {
      new SMGKubeCommands(log, arr(0), confParser.conf.clusterConfByUid(arr(0)).authConf)
    })
    runner.runPluginFetchCommand(arr(1), timeoutSec, parentData)
  }

  override def htmlContent(httpParams: Map[String,String]): String = {
    <h3>Plugin {pluginId}: Configuration</h3>
      <h4>Conf</h4>
      <ul>
        <li>autoconfTargetsD={confParser.conf.autoconfTargetsD}</li>
      </ul>
      <h4>Configured clusters</h4>
      <ul>
        {confParser.conf.clusterConfs.map { cconf =>
        <li>
          <h5>{cconf.inspect}</h5>
          { targetProcessor.getAutoconfTargetSummaries(cconf).map { seq =>
            <p>Targets:</p>
            <ol>
              {seq.map { ac =>
              <li>{ac.inspect}</li>
              }}
            </ol>
            }.getOrElse {
            <p>No data</p>
            }
          }
          <hr/>
          <p>Check the <a href="/plugin/autoconf">Autoconf plugin UI</a> for actual generated targets</p>
        </li>
      }}
      </ul>
  }.mkString
}
