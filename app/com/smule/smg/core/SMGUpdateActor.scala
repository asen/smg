package com.smule.smg.core

import akka.actor.Actor
import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.rrd.{SMGRrd, SMGRrdUpdate, SMGRrdUpdateData}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by asen on 10/23/15.
 */


/**
  * An actor responsible for doing rrd fetches/updates.
  */
class SMGUpdateActor(configSvc: SMGConfigService, commandExecutionTimes: TrieMap[String, Long]) extends Actor {

  import SMGUpdateActor._

  private val log = SMGLogger
  private val sendToSelfActor = context.actorOf(SendToSelfActor.props(self))

  private def ecForInterval(interval: Int): ExecutionContext = configSvc.executionContexts.ctxForInterval(interval)


  private def processSMGUpdateObjectMessage(obj: SMGObjectUpdate,
                                            ts: Option[Int], updateCounters: Boolean,
                                            parentData: Option[ParentCommandData]): Unit = {
    log.debug(s"SMGUpdateActor received SMGUpdateObjectMessage for ${obj.id}")
    Future {
      def fetchFn() = {
        val t0 = System.currentTimeMillis()
        try {
          obj match {
            case rrdObj: SMGRrdObject => rrdObj.fetchValues(parentData)
            case aggObj: SMGRrdAggObject => aggObj.fetchValues(configSvc)
            case x => throw new SMGFetchException(s"SMGERR: Invalid object update type: ${x.getClass}")
          }
        } finally {
          commandExecutionTimes(obj.id) = System.currentTimeMillis() - t0
        }
      }

      try {
        SMGUpdateActor.processObjectUpdate(obj, configSvc, ts, fetchFn _, log)
      } finally {
        if (updateCounters) {
          SMGStagedRunCounter.incIntervalCount(obj.interval)
        }
      }
    }(ecForInterval(obj.interval))
  }

  private def processSMGUpdateFetchMessage(interval: Int, rootCommands: Seq[SMGFetchCommandTree],
                                           ts: Option[Int], childConc: Int,
                                           updateCounters: Boolean,
                                           parentData: Option[ParentCommandData]): Unit = {
    val rootsSize = rootCommands.size
    val chunkSize = (rootsSize / childConc) + (if (rootsSize % childConc == 0) 0 else 1)
    val parallelRoots = rootCommands.grouped(chunkSize)
    log.debug(s"SMGUpdateActor received SMGUpdateFetchMessage for $rootsSize commands, " +
      s"processing with $childConc concurrency and $chunkSize chunk size")
    parallelRoots.foreach { fRoots =>
      Future {
        val t0 = SMGRrd.tssNow
        var slowWarnLogged = false
        var slowErrLogged = false
        var childSeqAborted = false
        fRoots.foreach { fRoot =>
          if (fRoot.node.isRrdObj) { // can happen for top-level rrd obj
            sendToSelfActor ! SMGUpdateObjectMessage(fRoot.node.asInstanceOf[SMGRrdObject], ts, updateCounters, parentData)
          } else { // not a rrd obj
            val pf = fRoot.node
            log.debug(s"SMGUpdateActor.SMGUpdateFetchMessage processing command for " +
              s"pre_fetch ${pf.id}, ${fRoot.size} child commands")
            val leafObjs = fRoot.leafNodes.map { c => c.asInstanceOf[SMGRrdObject] }
            var myData: Option[ParentCommandData] = None
            try {
              log.debug(s"SMGUpdateActor: Running pre_fetch command: ${pf.id}: ${pf.command.str}")
              try {
                var cmdTimeMs: Long = -1L
                val t0 = System.currentTimeMillis()
                try {
                  if (childSeqAborted)
                    throw SMGCmdException(pf.command.str, pf.command.timeoutSec, -1,
                      "Aborted due to too slow pre-fetch commands sequence.",
                      "Consider adjusting timeouts" +
                        pf.parentId.map(s => s" and/or increasing child_conc on the parent: $s").getOrElse("") + ".")
                  val out = pf.command.run(parentData.map(_.asStr))
                  if (pf.passData)
                    myData = Some(ParentCommandData(out))
                  cmdTimeMs = System.currentTimeMillis() - t0
                  if (cmdTimeMs > (pf.command.timeoutSec.toLong * 1000) * 0.5) { // more than 50% of timeout time
                    log.warn(s"SMGUpdateActor: slow pre_fetch command: ${pf.id}: ${pf.command.str} " +
                      s"(took=${cmdTimeMs.toDouble / 1000.0}, timeout=${pf.command.timeoutSec})")
                  }
                } finally {
                  if (cmdTimeMs < 0) cmdTimeMs = System.currentTimeMillis() - t0 // only if run threw
                  commandExecutionTimes(pf.id) = cmdTimeMs
                }
                //this is reached only on successfull pre-fetch
                configSvc.sendPfMsg(SMGDataFeedMsgPf(SMGRrd.tssNow, pf.id, interval, leafObjs, 0, List(), None))
                if (updateCounters)
                  SMGStagedRunCounter.incIntervalCount(interval)
                val updTs = if (pf.ignoreTs) None else Some(SMGRrd.tssNow)
                val (childObjTrees, childPfTrees) = fRoot.children.partition(_.node.isRrdObj)
                if (childObjTrees.nonEmpty) {
                  val childObjSeq = childObjTrees.map(_.node.asInstanceOf[SMGRrdObject])
                  childObjSeq.foreach { rrdObj =>
                    sendToSelfActor ! SMGUpdateObjectMessage(rrdObj, updTs, updateCounters, myData)
                  }
                  log.debug(s"SMGUpdateActor.runPrefetched($interval): Sent update messages for " +
                    s"[${pf.id}] object children (${childObjSeq.size})")
                }
                if (childPfTrees.nonEmpty) {
                  sendToSelfActor ! SMGUpdateFetchMessage(interval, childPfTrees, updTs, pf.childConc,
                    updateCounters, myData)
                  log.debug(s"SMGUpdateActor.runPrefetched($interval): Sent update messages for " +
                    s"[${pf.id}] pre_fetch children (${childPfTrees.size})")
                }
              } catch {
                case ex: SMGCmdException => {
                  log.error(s"SMGUpdateActor: Failed pre_fetch command [${pf.id}]: ${ex.getMessage}")
                  val errTs = SMGRrd.tssNow
                  val errLst = List(pf.command.str + s" (${pf.command.timeoutSec})", ex.stdout, ex.stderr)
                  configSvc.sendPfMsg(SMGDataFeedMsgPf(errTs, pf.id, interval, leafObjs, ex.exitCode, errLst, None))
                  if (updateCounters) {
                    fRoot.allNodes.foreach { cmd =>
                      SMGStagedRunCounter.incIntervalCount(interval)
                      if (cmd.isRrdObj) {
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
          } // not a rrd obj

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
        } // fRoots.forEach
      }(ecForInterval(interval))
    }
  }


  override def receive: Receive = {

    case SMGUpdateObjectMessage(obj: SMGObjectUpdate,
    ts: Option[Int], updateCounters: Boolean, parentData: Option[ParentCommandData]) =>
      processSMGUpdateObjectMessage(obj, ts, updateCounters, parentData)

    case SMGUpdateFetchMessage(interval: Int, rootCommands: Seq[SMGFetchCommandTree],
    ts: Option[Int], childConc: Int, updateCounters: Boolean, parentData: Option[ParentCommandData]) =>
      processSMGUpdateFetchMessage(interval, rootCommands, ts, childConc, updateCounters, parentData)
  }
}

object SMGUpdateActor {

 // val SLOW_UPDATE_THRESH = 600

//  def props = Props[SMGUpdateActor]
  case class SMGUpdateObjectMessage(
                                     obj: SMGObjectUpdate,
                                     ts: Option[Int],
                                     updateCounters: Boolean,
                                     parentData: Option[ParentCommandData]
                                   )

  case class SMGUpdateFetchMessage(
                                    interval:Int,
                                    rootCommands:Seq[SMGFetchCommandTree],
                                    ts: Option[Int],
                                    childConc: Int,
                                    updateCounters: Boolean,
                                    parentData: Option[ParentCommandData]
                                  )

  /**
    * Use this to do rrd updates, store successful values in the cache and send appropriate object monitoring messages.
    *
    * The fetchFn must return a List[Double] of correct length or throw SMGFetchException
    * (or the more specific SMGCmdException) on failure to fetch the values.
    *
    * @param obj
    * @param smgConfSvc
    * @param ts
    * @param fetchFn
    * @param log
    */
  def processObjectUpdate(obj: SMGObjectUpdate,
                          smgConfSvc: SMGConfigService,
                          ts: Option[Int],
                          fetchFn: () => List[Double],
                          log: SMGLoggerApi
                         ): Unit = {
    try {
      val rrd = new SMGRrdUpdate(obj, smgConfSvc)
      rrd.checkOrCreateRrd()
      try {
        val values = fetchFn()
        smgConfSvc.cacheValues(obj, ts.getOrElse(SMGRrd.tssNow), values)
        processRrdUpdate(rrd, smgConfSvc, ts, values, log)
      } catch {
        case cex: SMGCmdException => {
          smgConfSvc.invalidateCachedValues(obj)
          log.error(s"SMGUpdateActor: Failed fetch command [${obj.id}]: ${cex.getMessage}")
          smgConfSvc.sendObjMsg(
            SMGDataFeedMsgObj(ts.getOrElse(SMGRrd.tssNow), obj, List(), cex.exitCode,
              List(cex.cmdStr + s" (${cex.timeoutSec})", cex.stdout, cex.stderr))
          )
        }
        case fex: SMGFetchException => {
          smgConfSvc.invalidateCachedValues(obj)
          log.error(s"SMGUpdateActor: Fetch exception from  [${obj.id}]: ${fex.getMessage}")
          smgConfSvc.sendObjMsg(SMGDataFeedMsgObj(SMGRrd.tssNow, obj, List(), -1, List("fetch_error", fex.getMessage)))
        }
        case ex: Throwable => {
          smgConfSvc.invalidateCachedValues(obj)
          log.ex(ex, s"SMGUpdateActor: Unexpected exception from fetch [${obj.id}]: ${ex.getMessage}")
          smgConfSvc.sendObjMsg(SMGDataFeedMsgObj(SMGRrd.tssNow, obj, List(), -1, List("unexpected_error.", ex.getMessage)))
        }
      }
    } catch {
      case ex: Throwable => {
        smgConfSvc.invalidateCachedValues(obj)
        log.ex(ex, s"SMGUpdateActor got an unexpected while checking ${obj.id} rrd file")
        smgConfSvc.sendObjMsg(
          SMGDataFeedMsgObj(ts.getOrElse(SMGRrd.tssNow), obj, List(), -1, List("unexpected_rrd_error", ex.getMessage))
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
    * @param ts
    * @param values
    * @param log
    */
  def processRrdUpdate(rrd: SMGRrdUpdate,
                       smgConfSvc: SMGConfigService,
                       ts: Option[Int],
                       values: List[Double],
                       log: SMGLoggerApi): Unit = {
    try {
      if (smgConfSvc.config.rrdConf.useBatchedUpdates)
        SMGUpdateBatchActor.sendUpdate(smgConfSvc.getBatchUpdateActor.get, rrd.obju, SMGRrdUpdateData(values, ts))
      else
        rrd.updateValues(values, ts)
      smgConfSvc.sendObjMsg(
        SMGDataFeedMsgObj(ts.getOrElse(SMGRrd.tssNow), rrd.obju, values, 0, List())
      )
    } catch {
      case cex: SMGCmdException => {
        smgConfSvc.invalidateCachedValues(rrd.obju)
        log.error(s"SMGUpdateActor: Exception in update: [${rrd.obju.id}]: ${cex.toString}")
        smgConfSvc.sendObjMsg(
          SMGDataFeedMsgObj(SMGRrd.tssNow, rrd.obju, List(), -1, List("update_error", cex.getMessage))
        )
      }
      case t: Throwable => {
        smgConfSvc.invalidateCachedValues(rrd.obju)
        log.ex(t, s"SMGUpdateActor: Unexpected exception in update: [${rrd.obju.id}]: ${t.toString}")
        smgConfSvc.sendObjMsg(
          SMGDataFeedMsgObj(SMGRrd.tssNow, rrd.obju, List(), -1, List("unexpected_update_error", t.getMessage))
        )
      }
    }
  }

  def processRrdBatchUpdate(rrd: SMGRrdUpdate,
                            smgConfSvc: SMGConfigService,
                            batch: Seq[SMGRrdUpdateData],
                            log: SMGLoggerApi): Unit = {
    try {
      rrd.updateBatch(batch)
      batch.foreach { ud =>
        smgConfSvc.sendObjMsg(
          SMGDataFeedMsgObj(ud.ts.getOrElse(SMGRrd.tssNow), rrd.obju, ud.values, 0, List())
        )
      }
    } catch {
      case cex: SMGCmdException => {
        smgConfSvc.invalidateCachedValues(rrd.obju)
        log.error(s"SMGUpdateActor: Exception in batch update: [${rrd.obju.id}]: ${cex.toString}")
        smgConfSvc.sendObjMsg(
          SMGDataFeedMsgObj(SMGRrd.tssNow, rrd.obju, List(), -1, List("update_error", cex.getMessage))
        )
      }
      case t: Throwable => {
        smgConfSvc.invalidateCachedValues(rrd.obju)
        log.ex(t, s"SMGUpdateActor: Unexpected exception in batch update: [${rrd.obju.id}]: ${t.toString}")
        smgConfSvc.sendObjMsg(
          SMGDataFeedMsgObj(SMGRrd.tssNow, rrd.obju, List(), -1, List("unexpected_update_error", t.getMessage))
        )
      }
    }
  }
}
