package com.smule.smg.core

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGUpdateActor.{SMGAggObjectMessage, SMGFetchCommandMessage, SMGObjectDataMessage}
import com.smule.smg.rrd.{SMGRrd, SMGRrdUpdate, SMGRrdUpdateData}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

class SMGUpdateActor(configSvc: SMGConfigService, commandExecutionTimes: TrieMap[String, Long]) extends Actor  {

  private val log = SMGLogger
  private val sendToSelfActor = context.actorOf(SendToSelfActor.props(self))
  private val actorSystem = context.system

  private def ecForInterval(interval: Int): ExecutionContext =
    configSvc.executionContexts.ctxForInterval(interval)

  private def processTreeRoot(interval: Int,
                              fRoot: SMGTree[SMGFetchCommand],
                              ts: Option[Int],
                              updateCounters: Boolean,
                              parentData: Option[ParentCommandData],
                              childSeqAborted: Boolean
                             ): Unit = {
    val pf = fRoot.node
//    log.debug(s"SMGUpdateActor.SMGUpdateFetchMessage processing command with " +
//      s"id ${pf.id}, ${fRoot.size} child commands")
    val leafObjs = fRoot.leafNodes.map { c => c.asInstanceOf[SMGRrdObject] }
    var myData: Option[ParentCommandData] = None
    try {
//      log.debug(s"SMGUpdateActor: Running fetch command: ${pf.id}: ${pf.command.str}")
      try {
        var cmdTimeMs: Long = -1L
        val t0 = System.currentTimeMillis()
        val updTss = if (pf.ignoreTs) None else if (ts.isDefined) ts else Some(SMGRrd.tssNow)
        try {
          if (childSeqAborted)
            throw SMGCmdException(pf.command.str, pf.command.timeoutSec, -1,
              "Aborted due to too slow pre-fetch commands sequence.",
              "Consider adjusting timeouts" +
                pf.parentId.map(s => s" and/or increasing child_conc on the parent: $s").getOrElse("") + ".")

          val myParentData = if (pf.ignorePassedData) None else parentData
          val out = configSvc.runFetchCommand(pf.command, myParentData)

          if (pf.passData || pf.isUpdateObj)
            myData = Some(ParentCommandData(out, updTss))
          cmdTimeMs = System.currentTimeMillis() - t0
          if (cmdTimeMs > (pf.command.timeoutSec.toLong * 1000) * 0.5) { // more than 50% of timeout time
            log.warn(s"SMGUpdateActor: slow command: ${pf.id}: ${pf.command.str} " +
              s"(took=${cmdTimeMs.toDouble / 1000.0}, timeout=${pf.command.timeoutSec})")
          }
        } finally {
          if (cmdTimeMs < 0) cmdTimeMs = System.currentTimeMillis() - t0 // only if run threw
          commandExecutionTimes(pf.id) = cmdTimeMs
        }
        // this is reached only on successfull pre-fetch
        // validate the result of the command if an update object, before sending success msg
        val objectDataMessageOpt = if (fRoot.node.isUpdateObj){
          // handle unexpected class cast exceptiosn etc
          try {
            val rrdObj = fRoot.node.asInstanceOf[SMGObjectUpdate]
            val resData = myData.get.res.asUpdateData(rrdObj.vars.size)
            Some(SMGObjectDataMessage(rrdObj, updTss, resData))
          } catch { case t: Throwable =>
            throw SMGCmdException(pf.command.str,
              pf.command.timeoutSec, -1, Try(myData.get.res.asStr).getOrElse(""),
              s"Invalid command result: (${t.getMessage})")
          }
        } else None
        // this is reached only on successfull pre-fetch AND valid result in the isUpdateObj case
        // send command success msg
        configSvc.sendCommandMsg(SMGDataFeedMsgCmd(updTss.getOrElse(SMGRrd.tssNow), pf.id,
          interval, leafObjs, 0, List(), None))
        // send object data message to self if applicable
        if (objectDataMessageOpt.isDefined)
          sendToSelfActor ! objectDataMessageOpt.get
        if (updateCounters)
          SMGStagedRunCounter.incIntervalCount(interval)
        val (childObjTrees, childPfTrees) = fRoot.children.partition(_.node.isUpdateObj)
        // leaf/update objects do not obey child concurrency and are run in parallel
        // so each gets its own separate message
        if (childObjTrees.nonEmpty) {
          childObjTrees.foreach { rrdObjTree =>
            SMGUpdateActor.sendSMGFetchCommandMessage(actorSystem, sendToSelfActor,
              ecForInterval(interval), interval, Seq(rrdObjTree), updTss,
              pf.childConc, updateCounters, myData, log, forceDelay = None)
          }
//          log.debug(s"SMGUpdateActor.processTreeRoot($interval): Sent update messages for " +
//            s"[${pf.id}] object children (${childObjTrees.size})")
        }
        if (childPfTrees.nonEmpty) {
          SMGUpdateActor.sendSMGFetchCommandMessage(actorSystem, sendToSelfActor,
            ecForInterval(interval), interval, childPfTrees, updTss,
            pf.childConc, updateCounters, myData, log, forceDelay = None)
//          log.debug(s"SMGUpdateActor.processTreeRoot($interval): Sent update messages for " +
//            s"[${pf.id}] pre_fetch children (${childPfTrees.size})")
        }
      } catch {
        case ex: SMGCmdException => {
          log.error(s"SMGUpdateActor.processTreeRoot: Failed fetch command [${pf.id}]: ${ex.getMessage}")
          val errTs = SMGRrd.tssNow
          val errLst = List(pf.command.str + s" (${pf.command.timeoutSec})", ex.stdout, ex.stderr)
          configSvc.sendCommandMsg(SMGDataFeedMsgCmd(errTs, pf.id, interval, leafObjs, ex.exitCode, errLst, None))
          if (updateCounters) {
            fRoot.allNodes.foreach { cmd =>
              SMGStagedRunCounter.incIntervalCount(interval)
              if (cmd.isUpdateObj) {
                configSvc.invalidateCachedValues(cmd.asInstanceOf[SMGObjectUpdate])
              }
            }
          }
        }
      }
    } catch {
      case ex: Throwable => {
        log.ex(ex, "SMGUpdateActor: Unexpected exception in pre_fetch: [" + pf.id + "]: " + ex.toString)
      }
    }
  }

  private def processTreeSequenceAsync(interval: Int, fRoots: Seq[SMGTree[SMGFetchCommand]],
                                           ts: Option[Int], childConc: Int,
                                           updateCounters: Boolean,
                                           parentData: Option[ParentCommandData]): Unit = {
    Future {
      val t0 = SMGRrd.tssNow
      var slowWarnLogged = false
      var slowErrLogged = false
      var childSeqAborted = false
      fRoots.foreach { fRoot =>
        processTreeRoot(interval, fRoot, ts, updateCounters, parentData, childSeqAborted)
        val deltaT = SMGRrd.tssNow - t0
        lazy val tooSlowMsg = s"SMGUpdateActor: pre_fetch child sequence is taking more than $deltaT seconds. " +
          s"Current node=${fRoot.node.id}, childConc=$childConc"
        if (!slowErrLogged) {
          if (deltaT >= (interval * 3) / 4) {
            log.error(tooSlowMsg + " (aborting subsequent commands)")
            childSeqAborted = true
            slowErrLogged = true
          } else if ((deltaT >= interval / 4) && (!slowWarnLogged)) {
            log.warn(tooSlowMsg)
            slowWarnLogged = true
          }
        }
      }
    }(ecForInterval(interval))
  }

  private def processSMGUpdateFetchMessage(interval: Int, rootCommands: Seq[SMGTree[SMGFetchCommand]],
                                           ts: Option[Int], requestedChildConc: Int,
                                           updateCounters: Boolean,
                                           parentData: Option[ParentCommandData]): Unit = {

    val childConc = if (requestedChildConc < 1) 1 else requestedChildConc
    val rootsSize = rootCommands.size
    val chunkSize = (rootsSize / childConc) + (if (rootsSize % childConc == 0) 0 else 1)
    val parallelRoots = rootCommands.grouped(chunkSize)
//    log.debug(s"SMGUpdateActor received SMGUpdateFetchMessage for $rootsSize commands, " +
//      s"processing with $childConc concurrency and $chunkSize chunk size")
    parallelRoots.foreach { fRoots =>
      processTreeSequenceAsync(interval, fRoots, ts, childConc, updateCounters, parentData)
    }
  }

  private def processObjectDataMessage(dm: SMGObjectDataMessage): Unit = {
//    log.debug(s"SMGUpdateActor received SMGUpdateObjectMessage for ${dm.obj.id}")
    Future {
      SMGUpdateActor.processObjectUpdate(dm.obj, configSvc, dm.ts, dm.objectData, log)
    }(ecForInterval(dm.obj.interval))
  }

  private def processFetchCommandMessage(fm: SMGFetchCommandMessage): Unit = {
    processSMGUpdateFetchMessage(fm.interval,
      fm.rootCommands, fm.ts, fm.childConc, fm.updateCounters, fm.parentData)
  }

  private def processAggObjectMessage(aggm: SMGAggObjectMessage): Unit = {
    try {
      val resTupl = try {
        (Some(configSvc.fetchAggValues(aggm.obj)), None)
      } catch { case t: Throwable =>
        log.ex(t, s"SMGUpdateActor.processAggObjectMessage (${aggm.obj.id}): " +
          s"Unexpected exception from fetchAggValues: ${t.getMessage}")
        (None, Some(t))
      }
      val resData = resTupl._1
      if (resData.isDefined) {
        configSvc.sendCommandMsg(
          SMGDataFeedMsgCmd(resData.get.ts.getOrElse(SMGRrd.tssNow), aggm.obj.id,
            aggm.obj.interval, Seq(aggm.obj), 0, List(), aggm.obj.pluginId)
        )
        sendToSelfActor ! SMGObjectDataMessage(aggm.obj, None, resData.get)
      } else {
        configSvc.sendCommandMsg(
          SMGDataFeedMsgCmd(SMGRrd.tssNow, aggm.obj.id,
            aggm.obj.interval, Seq(aggm.obj), -1,
            List("Unexpected error", resTupl._2.get.getMessage), aggm.obj.pluginId)
        )
      }
    } finally {
      SMGStagedRunCounter.incIntervalCount(aggm.obj.interval)
    }
  }

  override def receive: Receive = {
    case fm: SMGFetchCommandMessage => processFetchCommandMessage(fm)
    case dm: SMGObjectDataMessage => processObjectDataMessage(dm)
    case aggm: SMGAggObjectMessage => processAggObjectMessage(aggm)
    case x => log.error(s"SMGUpdateActor: unexpected message: $x")
  }
}

object SMGUpdateActor {

  case class SMGFetchCommandMessage(
                                    interval:Int,
                                    rootCommands:Seq[SMGTree[SMGFetchCommand]],
                                    ts: Option[Int],
                                    childConc: Int,
                                    updateCounters: Boolean,
                                    parentData: Option[ParentCommandData]
                                  )

  case class SMGObjectDataMessage(obj: SMGObjectUpdate,
                                  ts: Option[Int], objectData: SMGRrdUpdateData)
  
  case class SMGAggObjectMessage(obj: SMGRrdAggObject)


  def sendSMGFetchCommandMessage(
                                  system: ActorSystem,
                                  targetActor: ActorRef,
                                  ec: ExecutionContext,
                                  interval:Int,
                                  rootCommands:Seq[SMGTree[SMGFetchCommand]],
                                  ts: Option[Int],
                                  childConc: Int,
                                  updateCounters: Boolean,
                                  parentData: Option[ParentCommandData],
                                  log: SMGLoggerApi,
                                  forceDelay: Option[Double]
                                ) : Unit = {
    val byDelay = rootCommands.groupBy(rc => if (forceDelay.isDefined) forceDelay.get else rc.node.delay)
    byDelay.foreach { case (delay, seq) =>
      val msg = SMGFetchCommandMessage(interval, seq, ts, childConc,
        updateCounters, parentData)
      if (delay == 0.0){
        targetActor ! msg
      } else {
        var actualDelayMs: Long = if (delay > 0)
          (delay * 1000).toInt
        else {
          // negative delay means random value between 0 and interval - abs(delay)
          Random.nextInt(((interval.toDouble + delay) * 1000).toInt)
        }
        if ((actualDelayMs < 0) || (actualDelayMs >= (interval * 1000))){
          log.warn(s"SMGUpdateActor.sendSMGFetchCommandMessage: invalid delay value ($actualDelayMs), " +
            s"resetting to 1. Commands: ${seq.map(_.node.id).mkString(",")}")
          actualDelayMs = 1
        }
        val dur = FiniteDuration(actualDelayMs, TimeUnit.MILLISECONDS)
        system.scheduler.scheduleOnce(dur, targetActor, msg)(ec)
      }
    }
  }

  /**
    * Use this to do rrd updates, store successful values in the cache and send appropriate object monitoring messages.
    *
    * The fetchFn must return a List[Double] of correct length or throw SMGFetchException
    * (or the more specific SMGCmdException) on failure to fetch the values.
    *
    * @param obj
    * @param smgConfSvc
    * @param ts
    * @param res
    * @param log
    */
  def processObjectUpdate(obj: SMGObjectUpdate,
                          smgConfSvc: SMGConfigService,
                          ts: Option[Int],
                          res: SMGRrdUpdateData,
                          log: SMGLoggerApi
                         ): Unit = {
    try {
      val rrd = new SMGRrdUpdate(obj, smgConfSvc)
      rrd.checkOrCreateRrd()
      try {
        val cacheTs = res.ts.getOrElse(ts.getOrElse(SMGRrd.tssNow))
        if (cacheTs < 0)
          log.error(s"SMGUpdateActor.processObjectUpdate (${obj.id}): negative cacheTs $cacheTs (ts=$ts res=$res)")
        smgConfSvc.cacheValues(obj, cacheTs, res.values)
        processRrdUpdate(rrd, smgConfSvc, ts, res, log)
      } catch {
        case cex: SMGCmdException => {
          smgConfSvc.invalidateCachedValues(obj)
          log.error(s"SMGUpdateActor: Failed update command [${obj.id}]: ${cex.getMessage}")
          smgConfSvc.sendCommandMsg(
            SMGDataFeedMsgCmd(SMGRrd.tssNow, obj.id, obj.interval, List(obj), cex.exitCode,
              List(cex.getMessage), None)
          )
        }
        case ex: Throwable => {
          smgConfSvc.invalidateCachedValues(obj)
          log.ex(ex, s"SMGUpdateActor: Unexpected exception from update [${obj.id}]: ${ex.getMessage}")
          smgConfSvc.sendCommandMsg(
            SMGDataFeedMsgCmd(SMGRrd.tssNow, obj.id, obj.interval, List(obj), -1,
              List(ex.getMessage), None)
          )
        }
      }
    } catch {
      case ex: Throwable => {
        smgConfSvc.invalidateCachedValues(obj)
        log.ex(ex, s"SMGUpdateActor got an unexpected while checking ${obj.id} rrd file")
        smgConfSvc.sendCommandMsg(
          SMGDataFeedMsgCmd(SMGRrd.tssNow, obj.id, obj.interval, List(obj), -1,
            List(ex.getMessage), None)
        )
      }
    }

  } // processObjectUpdate

  /**
    * Do the update part of a SNGObjectUpdate and send appropriate monitor messages
    * Does not throw
    *
    * @param rrd
    * @param smgConfSvc
    * @param udata
    * @param log
    */
  def processRrdUpdate(rrd: SMGRrdUpdate,
                       smgConfSvc: SMGConfigService,
                       ts: Option[Int],
                       udata: SMGRrdUpdateData,
                       log: SMGLoggerApi): Unit = {
    val realTss = if (udata.ts.isDefined) udata.ts else ts
    try {
      if (smgConfSvc.config.rrdConf.useBatchedUpdates)
        SMGUpdateBatchActor.sendUpdate(smgConfSvc.getBatchUpdateActor.get, rrd.obju, udata.withTss(realTss))
      else
        rrd.updateValues(udata.values, realTss)
      smgConfSvc.sendValuesMsg(
        SMGDataFeedMsgVals(rrd.obju, udata.withTss(realTss))
      )
    } catch {
      case cex: SMGCmdException => {
        smgConfSvc.invalidateCachedValues(rrd.obju)
        log.error(s"SMGUpdateActor: Exception in update: [${rrd.obju.id}]: ${cex.toString}")
        smgConfSvc.sendCommandMsg(
          SMGDataFeedMsgCmd(realTss.getOrElse(SMGRrd.tssNow), rrd.obju.id, rrd.obju.interval,
            List(rrd.obju), -1,
            List(cex.getMessage), None)
        )
      }
      case t: Throwable => {
        smgConfSvc.invalidateCachedValues(rrd.obju)
        log.ex(t, s"SMGUpdateActor: Unexpected exception in update: [${rrd.obju.id}]: ${t.toString}")
        smgConfSvc.sendCommandMsg(
          SMGDataFeedMsgCmd(realTss.getOrElse(SMGRrd.tssNow), rrd.obju.id, rrd.obju.interval,
            List(rrd.obju), -1,
            List(t.getMessage), None)
        )
      }
    }
  }

  // This is useful for plugins which do multiple updates to the same object in one shot
  def processRrdBatchUpdate(rrd: SMGRrdUpdate,
                            smgConfSvc: SMGConfigService,
                            batch: Seq[SMGRrdUpdateData],
                            log: SMGLoggerApi): Unit = {
    try {
      rrd.updateBatch(batch)
      batch.foreach { ud =>
        smgConfSvc.sendValuesMsg(
          SMGDataFeedMsgVals(rrd.obju, ud)
        )
      }
    } catch {
      case cex: SMGCmdException => {
        smgConfSvc.invalidateCachedValues(rrd.obju)
        log.error(s"SMGUpdateActor: Exception in batch update: [${rrd.obju.id}]: ${cex.toString}")
        smgConfSvc.sendCommandMsg(
          SMGDataFeedMsgCmd(SMGRrd.tssNow, rrd.obju.id, rrd.obju.interval,
            List(rrd.obju), -1,
            List(cex.getMessage), None)
        )
      }
      case t: Throwable => {
        smgConfSvc.invalidateCachedValues(rrd.obju)
        log.ex(t, s"SMGUpdateActor: Unexpected exception in batch update: [${rrd.obju.id}]: ${t.toString}")
        smgConfSvc.sendCommandMsg(
          SMGDataFeedMsgCmd(SMGRrd.tssNow, rrd.obju.id, rrd.obju.interval,
            List(rrd.obju), -1,
            List(t.getMessage), None)
        )
      }
    }
  }

}