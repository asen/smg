package com.smule.smgplugins.syslog

import com.smule.smg.config.{SMGConfIndex, SMGConfigService}
import com.smule.smg.core.{SMGIndex, SMGObjectView}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smgplugins.syslog.config.{PipelineConfig, SMGSyslogPluginConfig}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

class SMGSyslogPlugin(val pluginId: String,
                      val interval: Int,
                      val pluginConfFile: String,
                      val smgConfSvc: SMGConfigService
                     ) extends SMGPlugin {

  private val log = new SMGPluginLogger(pluginId)
  private val ec = smgConfSvc.executionContexts.pluginsSharedCtx

  private def getNewConf: SMGSyslogPluginConfig = {
    SMGSyslogPluginConfig.parsePluginConfFile(pluginId, pluginConfFile, interval, log)
  }

  private var currentConfig: SMGSyslogPluginConfig = getNewConf
  private val currentServers = TrieMap[String, SyslogServerContext]()
  createSyslogServers()

  private def createSyslogServer(conf: PipelineConfig): SyslogServerContext = {
    new SyslogServerContext(
      smgConfSvc = smgConfSvc,
      conf = conf,
      log = log
    )
  }

  private def createSyslogServers(): Unit = {
    val byId = currentConfig.pipelineConfigs.groupBy(_.serverId)
    val toShutdown = currentServers.keySet -- byId.keySet
    byId.foreach { case (id, seq) =>
      val pc = seq.head
      if (seq.tail.nonEmpty){
        log.error(s"SMGSyslogPlugin.createSyslogServers: duplicate serverId: $id, ignoring duplicates")
      }
      val ctx = currentServers.getOrElseUpdate(id, {
        log.debug(s"SMGSyslogPlugin.createSyslogServers: Creating server with id $id ...")
        val ret = createSyslogServer(pc)
        log.info(s"SMGSyslogPlugin.createSyslogServers: Created server with id $id ")
        ret
      })
      if (ctx.conf != pc){
        // restart server
        log.debug(s"SMGSyslogPlugin.createSyslogServers: Restarting down server with id $id ...")
        ctx.shutdown(ec)
        log.debug(s"SMGSyslogPlugin.createSyslogServers: Shut down server with id $id (restarting)")
        currentServers.put(id, createSyslogServer(pc))
        log.info(s"SMGSyslogPlugin.createSyslogServers: Restarted server with id $id")
      }
    }
    toShutdown.foreach { id =>
      // shutdown servers
      log.debug(s"SMGSyslogPlugin.createSyslogServers: Shutting down server with id $id ...")
      val ctx = currentServers(id)
      ctx.shutdown(ec)
      log.info(s"SMGSyslogPlugin.createSyslogServers: Shut down server with id $id")
    }
  }

  override def run(): Unit = {
    log.debug("SMGSyslogPlugin.run")
    if (!checkAndSetRunning){
      log.error("SMGSyslogPlugin.run - overlapping runs detected - aborting")
      return
    }
    log.info("SMGSyslogPlugin.run - starting")
    Future {
      try {
        // send tick messages
        // and check for dirty caches to tell sMG to reload conf and make these visible
        var hasDirty: Boolean = false
        currentServers.values.foreach { ctx =>
          ctx.tick()
          if (ctx.cacheIsDirty) {
            log.info(s"SMGSyslogPlugin.run: server ${ctx.conf.serverId} has dirty object caches - scheduling reload")
            hasDirty = true
          }
        }
        if (hasDirty) {
          smgConfSvc.reloadLocal()
        }
      } catch { case t: Throwable =>
        log.ex(t, s"SMGSyslogPlugin.run - unexpected error: ${t.getMessage}")
      } finally {
        log.info("SMGSyslogPlugin.run - finished")
        finished()
      }
    }(ec)
  }

  override def reloadConf(): Unit = {
    currentConfig = getNewConf
    createSyslogServers()
  }

  override def objects: Seq[SMGObjectView] = currentConfig.pipelineConfigs.flatMap { pc =>
    val ctx = currentServers.get(pc.serverId)
    if (ctx.isEmpty) {
      Seq()
    } else {
      ctx.get.getObjectViews
    }
  }

  override def indexes: Seq[SMGConfIndex]  = {
    Seq() // TODO
  }
}
