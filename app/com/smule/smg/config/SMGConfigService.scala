package com.smule.smg.config

import akka.actor.ActorSystem
import com.smule.smg._
import com.smule.smg.core._
import com.smule.smg.monitor.{SMGMonAlertConfVar, SMGMonNotifyCmd, SMGMonNotifyConf, SMGMonNotifySeverity}
import com.smule.smg.plugin.SMGPlugin
import play.Mode

import scala.io.Source
import scala.util.Try

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
  def sendObjMsg(msg: SMGDataFeedMsgObj): Unit = {
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
  def sendPfMsg(msg: SMGDataFeedMsgPf): Unit = {
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
  def sendRunMsg(msg: SMGDataFeedMsgRun): Unit = dataFeedListeners.foreach(dfl => Try(dfl.receiveRunMsg(msg)))

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

  def sourceFromFile(fn:String): String // implemented in parser
  def isDevMode: Boolean

  val URL_TOO_LONG_MSG: String = "This page URL is not share-able because the resulting URL would be too long. " +
    "It will not auto-refresh either."

  val TREES_PAGE_DFEAULT_LIMIT = 200
}
