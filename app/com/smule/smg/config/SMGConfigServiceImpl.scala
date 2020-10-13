package com.smule.smg.config

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ActorSystem, DeadLetter, Props}
import com.smule.smg.core._
import com.smule.smg.plugin.{SMGPlugin, SMGPluginConfig}
import com.smule.smg.rrd.SMGRrdUpdate
import com.typesafe.config.ConfigFactory
import javax.inject.{Inject, Singleton}
import play.{Environment, Mode}
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/**
  * A singleton (injected by Guice) responsible for parsing and caching local SMG configuration
  *
  * @param configuration - Play configuration object to bootstrap our config from
  */
@Singleton
class SMGConfigServiceImpl @Inject() (configuration: Configuration,
                                      override val actorSystem: ActorSystem,
                                      override val executionContexts: ExecutionContexts,
                                      environment: Environment,
                                      lifecycle: ApplicationLifecycle
                                     ) extends SMGConfigService {

  override val useInternalScheduler: Boolean =
    configuration.getOptional[Boolean]("smg.useInternalScheduler").getOrElse(true)

  private val callSystemGcOnReload: Boolean =
    configuration.getOptional[Boolean]("smg.callSystemGcOnReload").getOrElse(true)

  override val smgVersionStr: String = {
    try {
      val conf = ConfigFactory.load("build-number.conf")
      val vers = conf.getString("smg.version")
      val bnum = conf.getString("smg.build")
      val ret = s"$vers-$bnum"
      log.info(s"Starting SMG version $ret")
      ret
    } catch {
      case t: Throwable =>
        log.ex(t, "Unexpected exception reading build-number.conf")
        "unknown"
    }
  }

  if (configuration.has("smg.timeoutCommand")){
    val tmtCmd = configuration.get[String]("smg.timeoutCommand")
    log.info("Overriding SMGCmd timeout command using " + tmtCmd)
    SMGCmd.setTimeoutCommand(tmtCmd)
  }

  if (configuration.has("smg.executorCommand")) {
    val execSeq = configuration.get[Seq[String]]("smg.executorCommand")
    log.info("Overriding SMGCmd executor command using " + execSeq)
    SMGCmd.setExecutorCommand(execSeq)
  }

  /**
    * XXX looks like java is leaking direct memory buffers (or possibly - just slowing
    * down external commands when reclaiming these on the fly) when reloading conf.
    * This is an attempt to fix that after realizing that a "manual gc" via jconsole clears overlap issues.
    * This method can be disabled via application.conf
    */
  private def callSystemGc(ctx: String): Unit = {
    // TODO synchronize ?
    if (callSystemGcOnReload) {
      // XXX looks like java is leaking direct memory buffers (or possibly - just slowing
      // down external commands when reclaiming these on the fly) when reloading conf.
      // This is an attempt to fix that after realizing that a "manual gc" via jconsole clears overlap issues
      log.info(s"ConfigService ($ctx) calling System.gc() ... START")
      System.gc()
      log.info(s"ConfigService ($ctx) calling System.gc() ... DONE")
    } else {
      log.info(s"ConfigService ($ctx) calling of System.gc() is disabled via smg.callSystemGcOnReload=false")
    }
  }

  private val pluginsApplicationConf = if (configuration.has("smg.plugins"))
    configuration.underlying.getConfigList("smg.plugins").asScala.map(v => SMGPluginConfig(
      v.getString("id"),
      v.getString("class"),
      v.getInt("interval"),
      v.getString("config")))
  else Seq[SMGPluginConfig]()


  private def createPlugins: Seq[SMGPlugin] = {
    pluginsApplicationConf.filter{ ac =>
      val ret = (ac.confFile != "") && new File(ac.confFile).exists()
      if (!ret) {
        log.warn("SMGConfigServiceImpl.createPlugins: Ignoring plugin application.conf entry specifying non-existing confFile: " + ac)
      }
      ret
    }.flatMap { ac =>
      try {
        val klass = Class.forName(ac.className)
        val ret = klass.getConstructor(
          classOf[String],
          classOf[Int],
          classOf[String],
          classOf[SMGConfigService]
        ).newInstance(ac.id,
          ac.interval.asInstanceOf[Object],
          ac.confFile,
          this
        ).asInstanceOf[SMGPlugin]
        log.info(s"Created plugin: ${ret.pluginId} - klass=${ac.className}")
        Some(ret)
      } catch { case t: Throwable =>
        log.ex(t, s"Unexpected error while loading plugin: $ac")
        None
      }
    }
  }

  // Data feed listeners
  private val myDataFeedListeners = ListBuffer[SMGDataFeedListener]()

  private val dataFeedEnabled: Boolean = if (configuration.has("smg.dataFeedEnabled"))
    configuration.get[Boolean]("smg.dataFeedEnabled")
  else true

  override def dataFeedListeners: List[SMGDataFeedListener] = {
    if (dataFeedEnabled) myDataFeedListeners.toList else List()
  }

  override def registerDataFeedListener(lsnr: SMGDataFeedListener):Unit = {
    myDataFeedListeners.synchronized(myDataFeedListeners += lsnr)
  }

  // Config reload listeners

  private val myConfigReloadListeners = ListBuffer[SMGConfigReloadListener]()
  override  def registerReloadListener(lsnr: SMGConfigReloadListener): Unit = {
    myConfigReloadListeners.synchronized(myConfigReloadListeners += lsnr)
  }
  def reloadListerenrs: List[SMGConfigReloadListener] = myConfigReloadListeners.synchronized(myConfigReloadListeners.toList)

  override def notifyReloadListeners(ctx: String): Unit = {
    val myrlsnrs = reloadListerenrs
    myrlsnrs.foreach { lsnr =>
      try {
        lsnr.reload()
      } catch { case t: Throwable =>
          log.ex(t, s"ConfigService.notifyReloadListeners($ctx): exception in reload from lsnr=$lsnr")
      }
    }
    log.info(s"ConfigService.notifyReloadListeners($ctx) - notified ${myrlsnrs.size} listeners")
    callSystemGc(ctx)
  }

  override val plugins: Seq[SMGPlugin] = createPlugins

  override val pluginsById: Map[String, SMGPlugin] = plugins.groupBy(_.pluginId).map(t => (t._1, t._2.head))

  // XXX this is only updated on config reload (getNewConfig) to avoid the need of synchronization
  private val valuesCache = new SMGValuesCache(log)

  override def cacheValues(ou: SMGObjectUpdate, tss: Int, vals: List[Double]): Unit = {
    // check whether ou.id is still valid and don't cache values if object was expunged by config reload
    if (config.updateObjectsById.contains(ou.id)) {
      valuesCache.cacheValues(ou, tss, vals)
    } else {
      log.debug(s"ConfigService.cacheValues: ignoring obsolete object with id: ${ou.id}")
    }
  }

  override def invalidateCachedValues(ou: SMGObjectUpdate): Unit = {
    valuesCache.invalidateCache(ou)
  }

  override def getCachedValues(ou: SMGObjectUpdate, counterAsRate: Boolean): (List[Double], Option[Int]) = {
    valuesCache.getCachedValues(ou, counterAsRate)
  }

  private def cleanupCachedValuesMap(newConf: SMGLocalConfig): Unit = {
    valuesCache.purgeObsoleteObjs(newConf.updateObjects)
  }

  private def initExecutionContexts(intervals: Set[Int]): Unit = {
    val defaultThreadsPerInterval: Int = if (configuration.has("smg.defaultThreadsPerInterval"))
      configuration.get[Int]("smg.defaultThreadsPerInterval") else 4
    val myConf = configuration.get[Configuration]("smg.threadsPerIntervalMap")
    val threadsPerIntervalMap: Map[Int, Int] = (for (i <- intervals.toList; if myConf.has("interval_" + i))
      yield (i, myConf.get[Int]("interval_" + i))).toMap
    executionContexts.initializeUpdateContexts(intervals.toSeq, threadsPerIntervalMap, defaultThreadsPerInterval)
  }

  private val topLevelConfigFile: String = if (configuration.has("smg.config"))
    configuration.get[String]("smg.config")
  else
    "/etc/smg/config.yml"

  private val configParser = new SMGConfigParser(log)

  override val defaultInterval: Int = configParser.defaultInterval
  override val defaultTimeout: Int = configParser.defaultTimeout

  private def createNonExistingRrds(newConf: SMGLocalConfig): Unit = {
    newConf.updateObjects.foreach { ou =>
      if (ou.rrdFile.isDefined) {
        val upd = new SMGRrdUpdate(ou, this)
        upd.checkOrCreateRrd()
      }
    }
  }

  private var currentConfig = configParser.getNewConfig(plugins, topLevelConfigFile)
  createNonExistingRrds(currentConfig)
  initExecutionContexts(currentConfig.intervals)
  plugins.foreach(_.onConfigReloaded())

  /**
    * @inheritdoc
    */
  override def config: SMGLocalConfig = currentConfig

  private val reloadIsRunning: AtomicBoolean = new AtomicBoolean(false)

  /**
    * @inheritdoc
    */
  override def reloadLocal(): Unit = {
    if (!reloadIsRunning.getAndSet(true)) {
      val t0 = System.currentTimeMillis()
      log.debug("SMGConfigServiceImpl.reload: Starting at " + t0)
      try {
        val newConf = configParser.getNewConfig(plugins, topLevelConfigFile)
        createNonExistingRrds(newConf)
        initExecutionContexts(newConf.intervals)
        currentConfig.synchronized {  // not really needed ...
          currentConfig = newConf
        }
        cleanupCachedValuesMap(newConf)
        notifyReloadListeners("ConfigService.reload")
        plugins.foreach(_.onConfigReloaded())
        val t1 = System.currentTimeMillis()
        log.info("SMGConfigServiceImpl.reload: completed for " + (t1 - t0) + "ms. rrdConf=" + newConf.rrdConf +
          " imgDir=" + newConf.imgDir + " urlPrefix=" + newConf.urlPrefix +
          " humanDesc: " + newConf.humanDesc)
      } finally {
        reloadIsRunning.set(false)
      }
    } else {
      log.warn("SMGConfigServiceImpl.reload: Reload is already running in another thread, aborting")
    }
  }

  lifecycle.addStopHook { () =>
    Future.successful {
      log.info("SMGConfigServiceImpl: shutting down plugins ...")
      plugins.foreach(_.onShutdown())
      log.info("SMGConfigServiceImpl: done shutting down plugins.")
    }
  }
  // register an Akka DeadLetter listener, to detect issues
  private val deadLetterListener = actorSystem.actorOf(Props(classOf[SMGDeadLetterActor]))
  actorSystem.eventStream.subscribe(deadLetterListener, classOf[DeadLetter])

  override def sourceFromFile(fn: String): String = SMGFileUtil.getFileContents(fn)

  override def isDevMode: Boolean = environment.mode() == Mode.DEV

  private val PLUGIN_COMMAND_PREFIX = ":"

  private def runPluginFetchCommand(command: SMGCmd, parentData: Option[ParentCommandData]): CommandResult = {
    try {
      val arr = command.str.split("\\s+", 2)
      val pluginId = arr(0).stripPrefix(PLUGIN_COMMAND_PREFIX)
      val pluginOpt = pluginsById.get(pluginId)
      if (pluginOpt.isEmpty) {
        throw SMGCmdException(command.str, command.timeoutSec, -1, "",
          s"Command references invalid plugin id: $pluginId, cmd: ${command.str}")
      }
      val cmdStr = if (arr.length > 1) arr(1) else ""
      pluginOpt.get.runPluginFetchCommand(cmdStr, command.timeoutSec, parentData)
    } catch {
      case c: SMGCmdException => throw c
      case t: Throwable => throw SMGCmdException(command.str, command.timeoutSec, -1,
        "Unexpected runPluginFetchCommand exception.", t.getMessage)
    }
  }

  def runFetchCommand(command: SMGCmd, parentData: Option[ParentCommandData]): CommandResult = {
    if (command.str.startsWith(PLUGIN_COMMAND_PREFIX)) {
//      log.debug(s"RUN_COMMAND: tms=${command.timeoutSec} : (plugin) ${command.str}")
      runPluginFetchCommand(command, parentData)
    } else {
      CommandResultListString(command.run(parentData.map(_.res.asStr)), parentData.flatMap(_.useTss))
    }
  }
}

