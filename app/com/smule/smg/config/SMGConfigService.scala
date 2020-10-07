package com.smule.smg.config

import akka.actor.{ActorRef, ActorSystem}
import com.smule.smg.core._
import com.smule.smg.monitor.{SMGMonAlertConfVar, SMGMonNotifyCmd, SMGMonNotifyConf, SMGMonNotifySeverity}
import com.smule.smg.plugin.SMGPlugin
import com.smule.smg.rrd.{SMGRrd, SMGRrdUpdateData}

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

  private var batchUpdateActorRef: Option[ActorRef] = None
  def getBatchUpdateActor: Option[ActorRef] = batchUpdateActorRef
  def setBatchUpdateActor(aref: ActorRef): Unit = batchUpdateActorRef = Some(aref)
  def flushBatchUpdateActor(reason: String): Unit = if (batchUpdateActorRef.isDefined)
    SMGUpdateBatchActor.sendFlush(batchUpdateActorRef.get, reason)
  else
    log.error("SMGConfigService.flushBatchUpdateActor called with empty batchUpdateActorRef")

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
  def sendValuesMsg(msg: SMGDataFeedMsgVals): Unit = {
    // TODO: do this async?
    dataFeedListeners.foreach(dfl => Try(dfl.receiveValuesMsg(msg)))
  }

  /**
  * Send a data feed  command message to all registered listeners for processing
  * @param msg - the message to send
  */
  def sendCommandMsg(msg: SMGDataFeedMsgCmd): Unit = {
    // TODO: do this async?
    dataFeedListeners.foreach(dfl => Try(dfl.receiveCommandMsg(msg)))
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
  def getCachedValues(ou: SMGObjectUpdate, counterAsRate: Boolean): (List[Double], Option[Int])

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

  def runFetchCommand(command: SMGCmd, parentData: Option[ParentCommandData]): CommandResult

  def fetchAggValues(aggObj: SMGRrdAggObject, confSvc: SMGConfigService): SMGRrdUpdateData = {
    val cache = aggObj.ous.map(ou => confSvc.getCachedValues(ou, !aggObj.isCounter)).toList
    val sources = cache.map(_._1)
    val tssSeq: Seq[Long] = cache.flatMap(_._2.map(_.toLong)) // using long to avoid the sum overflowing 32 its
    val tss: Option[Int] = if (tssSeq.isEmpty){
      None
    } else Some( (tssSeq.sum / tssSeq.size).toInt )
    SMGRrdUpdateData(SMGRrd.mergeValues(aggObj.aggOp, sources), tss)
  }

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
      case SMGMonNotifySeverity.FAILED => vnc.unkn
      case SMGMonNotifySeverity.WARNING => vnc.warn
      case SMGMonNotifySeverity.ANOMALY => vnc.anom
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
          if (atSeverity != SMGMonNotifySeverity.FAILED) {
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
      case SMGMonNotifySeverity.FAILED => config.globalUnknNotifyConf
      case SMGMonNotifySeverity.WARNING => config.globalWarnNotifyConf
      case SMGMonNotifySeverity.ANOMALY => config.globalAnomNotifyConf
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

  private val ellipsifyAt = 80
  def ellipsify(s: String): String = if (s.length > ellipsifyAt) {
    s.take(ellipsifyAt - 3) + "..."
  } else s

  def sourceFromFile(fn:String): String // implemented in parser
  def isDevMode: Boolean

  val URL_TOO_LONG_MSG: String = "This page URL is not share-able because the resulting URL would be too long. " +
    "It will not auto-refresh either."

  val TREES_PAGE_DFEAULT_LIMIT = 200
}
