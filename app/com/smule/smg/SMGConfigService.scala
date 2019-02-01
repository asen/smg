package com.smule.smg

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}

import akka.actor.{ActorSystem, DeadLetter, Props}
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.Try

import monitor._

/**
  * Created by asen on 11/15/15.
  */

trait SMGConfigReloadListener {
  def reload(): Unit
}

/**
  * An interface for a service managing the local SMG config, to beinjected by Guice
  */
trait SMGConfigService {

  protected val log = SMGLogger

  def executionContexts: ExecutionContexts

  val smgVersionStr: String

  val defaultInterval: Int
  val defaultTimeout: Int

  /**
    * Get the current configuration as cached during startup or previous reload.
    *
    * @return - the current SMGLocalConfig object
    */
  def config: SMGLocalConfig

  /**
    * reload config.yml
    */
  def reload(): Unit

  /**
    * Whether SMG is using its internal Akka scheduler or external (e.g. cron-driven) scheduler.
    * This is specified in application.conf and requires restart to change
    */
  val useInternalScheduler: Boolean

  /**
  * Configured (in application.conf) plugins
  */
  val plugins: Seq[SMGPlugin]

  val pluginsById: Map[String, SMGPlugin]

  /**
  * register an object instance as "data feed listener", so that it gets notified on all monitor state events
  * SMGMonitor registers itself, but plugins can register too
  * @param lsnr - the instance to register
  */
  def registerDataFeedListener(lsnr: SMGDataFeedListener): Unit

  protected def dataFeedListeners: Seq[SMGDataFeedListener]

  /**
  * Send a data feed object message to all registered listeners for processing
  * @param msg - the message to send
  */
  def sendObjMsg(msg: SMGDFObjMsg): Unit = {
    if (config.updateObjectsById.contains(msg.obj.id)) {
      dataFeedListeners.foreach(dfl => Try(dfl.receiveObjMsg(msg)))
    } else {
      log.warn(s"ConfigService.sendObjMsg: ignoring message for no longer existing object: " +
        s"${msg.obj.id}${msg.obj.pluginId.map(plid => s" (plugin=$plid)").getOrElse("")}")
    }
  }

  /**
  * Send a data feed "Pre fetch" command message to all registered listeners for processing
  * @param msg - the message to send
  */
  def sendPfMsg(msg: SMGDFPfMsg): Unit = {
    if ((msg.pluginId.isEmpty && config.preFetches.contains(msg.pfId)) ||
        (msg.pluginId.isDefined && config.pluginPreFetches.getOrElse(msg.pluginId.get, Map()).contains(msg.pfId))) {
      dataFeedListeners.foreach(dfl => Try(dfl.receivePfMsg(msg)))
    } else {
      log.warn(s"ConfigService.sendPfMsg: ignoring message for no longer existing preFetch: " +
        s"${msg.pfId}${msg.pluginId.map(plid => s" (plugin=$plid)").getOrElse("")}")
    }
  }

  /**
  * Send a data feed "run" (e.g. finished/overlap etc) message to all registered listeners for processing
  * @param msg - the message to send
  */
  def sendRunMsg(msg: SMGDFRunMsg): Unit = dataFeedListeners.foreach(dfl => Try(dfl.receiveRunMsg(msg)))

  /**
    * Store recently fetched object value into cache.
    * @param ou - object update
    * @param tss - fetch timestamp (seconds)
    * @param vals - fetched values
    */
  def cacheValues(ou: SMGObjectUpdate, tss: Int, vals: List[Double]): Unit

  /**
    * Invalidate any previously cached values for this object
    * @param ou
    */
  def invalidateCachedValues(ou: SMGObjectUpdate): Unit

  /**
    * Get the latest cached values for given object
    * @param ou - object update
    * @return - list of values (can be NaNs if no valid cache)
    */
  def getCachedValues(ou: SMGObjectUpdate, counterAsRate: Boolean): List[Double]

  /**
    * published here for plugins to use
    */
  val actorSystem: ActorSystem

  /**
  * Register an object instance to be notified on config reloads
  * @param lsnr - the object reference to register
  */
  def registerReloadListener(lsnr: SMGConfigReloadListener): Unit

  def notifyReloadListeners(ctx: String): Unit

  /**
  * Get all applicable to the provided object value (at index vix) AlertConfigs (a.k.a. checks)
  * @param ou
  * @param vix
  * @return
  */
  def objectValueAlertConfs(ou: SMGObjectUpdate, vix: Int): Seq[SMGMonAlertConfVar] = {
    val acs = config.objectAlertConfs
    if (acs.contains(ou.id))
      acs(ou.id).varConf(vix)
    else
      Seq()
  }


  /**
    * Get all applicable to the provided object/value (at optional index vix) Notification commands and backoff
    * seconds. If there are multiple notification configs defined for the object/vars, combine the commands (a.k.a.
    * alert recipients) from all. If multiple conflicting backoff periods are specified, the longest one will be used.
    *
    * If any of the matching notification confs has notify-disable set to true, notifications are disabled.
    *
    * If the object does not have any configured notification confs (whether directly or via index), the default
    * recipients and backoff will be used.
    *
    * @param ou
    * @param vixOpt
    * @param atSeverity
    * @return
    */
  def objectVarNotifyCmdsAndBackoff(ou: SMGObjectUpdate, vixOpt: Option[Int],
                                    atSeverity: SMGMonNotifySeverity.Value): (Seq[SMGMonNotifyCmd], Int) = {
    val oncOpt = config.objectNotifyConfs.get(ou.id)
    val isDisabledAndBackoffOpt = oncOpt.map(_.getIsDisabledAndBackoff(vixOpt))

    def cmdsForSeverity(vnc: SMGMonNotifyConf) = atSeverity match {
      case SMGMonNotifySeverity.CRITICAL => vnc.crit
      case SMGMonNotifySeverity.UNKNOWN => vnc.unkn
      case SMGMonNotifySeverity.WARNING => vnc.warn
      case SMGMonNotifySeverity.ANOMALY => vnc.spike
      case _ => { // should never happen ???
        SMGLogger.error(s"objectVarNotifyCmdsAndBackoff(${ou.id}, $vixOpt): cmdsForSeverity called with bad severity: $atSeverity")
        Seq()
      }
    }

    val retCmds = if (isDisabledAndBackoffOpt.exists(_._1)) {
      SMGLogger.debug(s"objectVarNotifyCmdsAndBackoff${ou.id}, $vixOpt) notificattions are disabled ($atSeverity)")
      Seq() // there is a conf and it says disabled
    } else {
      val notifCmds = if (oncOpt.isDefined) {
        val oncCmdIds: Seq[String] = if (vixOpt.isDefined){
          oncOpt.get.varConf(vixOpt.get).flatMap( vnc => cmdsForSeverity(vnc))
        } else { //"Object level" notify comds, should only happen for unknown state??
          if (atSeverity != SMGMonNotifySeverity.UNKNOWN) {
            SMGLogger.error(s"objectVarNotifyCmdsAndBackoff${ou.id}, $vixOpt) called for object with bad severity: $atSeverity")
          }
          ou.vars.indices.flatMap { vix =>
            oncOpt.get.varConf(vix).flatMap { vnc => cmdsForSeverity(vnc) }
          }
        }.distinct
        if (oncCmdIds.nonEmpty)
          oncCmdIds.map { s =>
            val opt = config.notifyCommands.get(s)
            if (opt.isEmpty){
              SMGLogger.error(s"objectVarNotifyCmdsAndBackoff: ${ou.id}: config specifies non-existing notify command: $s")
            }
            opt
          }.filter(_.isDefined).map(_.get)
        else
          globalNotifyCmds(atSeverity)
      } else
        globalNotifyCmds(atSeverity)
      notifCmds.distinct
    }
    val notifBackoff = isDisabledAndBackoffOpt.flatMap(_._2).getOrElse(config.globalNotifyBackoff)
    (retCmds, notifBackoff)
  }

  /**
  * Get all "global" configured notficiations commands for the provided severity
  * @param atSeverity
  * @return
  */
  def globalNotifyCmds(atSeverity: SMGMonNotifySeverity.Value): Seq[SMGMonNotifyCmd] = {
    atSeverity match {
      case SMGMonNotifySeverity.SMGERR => config.globalSmgerrNotifyConf
      case SMGMonNotifySeverity.CRITICAL => config.globalCritNotifyConf
      case SMGMonNotifySeverity.UNKNOWN => config.globalUnknNotifyConf
      case SMGMonNotifySeverity.WARNING => config.globalWarnNotifyConf
      case SMGMonNotifySeverity.ANOMALY => config.globalSpikeNotifyConf
      case _ => Seq()
    }
  }

  def objectVarNotifyStrikes(ou: SMGObjectUpdate, vixOpt: Option[Int]): Int = {
    val oncOpt = config.objectNotifyConfs.get(ou.id)
    val vixes = if (vixOpt.isDefined) Seq(vixOpt.get) else ou.vars.indices
    val ret = vixes.map { vix =>
      oncOpt.map{ onc =>
        val seq = onc.varConf(vix)
        if (seq.isEmpty)
          config.globalNotifyStrikes
        else
          seq.map(_.notifyStrikes.getOrElse(config.globalNotifyStrikes)).min
      }.getOrElse(config.globalNotifyStrikes)
    }.min
    Math.max(ret,1)
  }

  val URL_TOO_LONG_MSG: String = "This page URL is not share-able because the resulting URL would be too long. " +
    "It will not auto-refresh either."

  val TREES_PAGE_DFEAULT_LIMIT = 200
}

/**
  * A singleton (injected by Guice) responsible for parsing and caching local SMG configuration
  *
  * @param configuration - Play configuration object to bootstrap our config from
  */
@Singleton
class SMGConfigServiceImpl @Inject() (configuration: Configuration,
                                      override val actorSystem: ActorSystem,
                                      override val executionContexts: ExecutionContexts,
                                      lifecycle: ApplicationLifecycle
                                     ) extends SMGConfigService {

  /**
  * @inheritdoc
  */
  override val useInternalScheduler: Boolean = configuration.getBoolean("smg.useInternalScheduler").getOrElse(true)

  private val callSystemGcOnReload: Boolean = configuration.getBoolean("smg.callSystemGcOnReload").getOrElse(true)

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

  if (configuration.getString("smg.timeoutCommand").isDefined){
    val tmtCmd = configuration.getString("smg.timeoutCommand").get
    log.info("Overriding SMGCmd timeout command using " + tmtCmd)
    SMGCmd.setTimeoutCommand(tmtCmd)
  }

  if (configuration.getStringList("smg.executorCommand").isDefined) {
    val execSeq = configuration.getStringList("smg.executorCommand").get
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

  private val pluginsApplicationConf = configuration.getConfigList("smg.plugins") match {
    case Some(conf) => conf.map(v => SMGPluginConfig(
      v.getString("id").get,
      v.getString("class").get,
      v.getInt("interval").get,
      v.getString("config").get)).toSeq
    case None => Seq[SMGPluginConfig]()
  }

  private def createPlugins: Seq[SMGPlugin] = {
    pluginsApplicationConf.filter{ ac =>
      val ret = (ac.confFile != "") && new File(ac.confFile).exists()
      if (!ret) {
        log.warn("SMGConfigServiceImpl.createPlugins: Ignoring plugin application.conf entry specifying non-existing confFile: " + ac)
      }
      ret
    }.map { ac =>
      val klass = Class.forName(ac.className)
      klass.getConstructor(
        classOf[String],
        classOf[Int],
        classOf[String],
        classOf[SMGConfigService]
      ).newInstance(ac.id,
        ac.interval.asInstanceOf[Object],
        ac.confFile,
        this
      ).asInstanceOf[SMGPlugin]
    }
  }

  // Data feed listeners
  private val myDataFeedListeners = ListBuffer[SMGDataFeedListener]()

  private val dataFeedEnabled: Boolean =  configuration.getBoolean("smg.dataFeedEnabled").getOrElse(true)

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
      } catch {
        case t: Throwable => {
          log.ex(t, s"ConfigService.notifyReloadListeners($ctx): exception in reload from lsnr=$lsnr")
        }
      }
    }
    log.info(s"ConfigService.notifyReloadListeners($ctx) - notified ${myrlsnrs.size} listeners")
    callSystemGc(ctx)
  }

  /**
  * @inheritdoc
  */
  override val plugins: Seq[SMGPlugin] = createPlugins

  override val pluginsById: Map[String, SMGPlugin] = plugins.groupBy(_.pluginId).map(t => (t._1, t._2.head))

  // XXX this is only updated on config reload (getNewConfig) to avoid the need of synchronization
  private val valuesCache = new SMGValuesCache()

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

  override def getCachedValues(ou: SMGObjectUpdate, counterAsRate: Boolean): List[Double] = {
    valuesCache.getCachedValues(ou, counterAsRate)
  }

  private def cleanupCachedValuesMap(newConf: SMGLocalConfig): Unit = {
    valuesCache.purgeObsoleteObjs(newConf.updateObjects)
  }

  private def initExecutionContexts(intervals: Set[Int]): Unit = {
    val defaultThreadsPerInterval: Int = configuration.getInt("smg.defaultThreadsPerInterval").getOrElse(4)
    val threadsPerIntervalMap: Map[Int, Int] = configuration.getConfig("smg.threadsPerIntervalMap") match {
      case Some(conf) => (for (i <- intervals.toList; if conf.getInt("interval_" + i).isDefined) yield (i, conf.getInt("interval_" + i).get)).toMap
      case None => Map[Int, Int]()
    }
    executionContexts.initializeUpdateContexts(intervals.toSeq, threadsPerIntervalMap, defaultThreadsPerInterval)
  }

  private val topLevelConfigFile: String = configuration.getString("smg.config").getOrElse("/etc/smg/config.yml")

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
  override def reload(): Unit = {
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
}
