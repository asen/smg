package com.smule.smg

import javax.inject.Inject

import akka.actor.{Actor, ActorSystem, Props}

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

  val log = SMGLogger

  override def receive: Receive = {

    case SMGUpdateObjectMessage(obj: SMGObjectUpdate,
                                ts: Option[Int], updateCounters: Boolean) => {
      log.debug(s"SMGUpdateActor received SMGUpdateObjectMessage for ${obj.id}")
      Future {
        def fetchFn() = {
          val t0 = System.currentTimeMillis()
          try {
            obj.fetchValues
          } finally {
            commandExecutionTimes(obj.id) = System.currentTimeMillis() - t0
          }
        }
        try {
          SMGUpdateActor.processObjectUpdate(obj, configSvc, ts, fetchFn, log)
        } finally {
          if (updateCounters) {
            SMGStagedRunCounter.incIntervalCount(obj.interval)
          }
        }
      }(ecForInterval(obj.interval))
    }

    case SMGUpdateFetchMessage(interval:Int, rootCommands: Seq[SMGFetchCommandTree],
                               ts: Option[Int], childConc: Int, updateCounters: Boolean) => {
      val rootsSize = rootCommands.size
      val savedSelf = self
      val chunkSize = (rootsSize / childConc) + (if (rootsSize % childConc == 0) 0 else 1)
      val parallelRoots = rootCommands.grouped(chunkSize)
      log.debug(s"SMGUpdateActor received SMGUpdateFetchMessage for $rootsSize commands, " +
        s"processing with $childConc concurrency and $chunkSize chunk size")
      parallelRoots.foreach { fRoots =>
        Future {
          val t0 = SMGRrd.tssNow
          var slowWarnLogged = false
          var slowErrLogged = false
          fRoots.foreach { fRoot =>
            if (fRoot.node.isRrdObj) { // can happen for top-level rrd obj
              savedSelf ! SMGUpdateObjectMessage(fRoot.node.asInstanceOf[SMGRrdObject], ts, updateCounters)
            } else { // not a rrd obj
              val pf = fRoot.node
              log.debug(s"SMGUpdateActor.SMGUpdateFetchMessage processing command for " +
                s"pre_fetch ${pf.id}, ${fRoot.size} child commands")
              val leafObjs = fRoot.leafNodes.map { c => c.asInstanceOf[SMGRrdObject] }
              try {
                log.debug(s"SMGUpdateActor: Running pre_fetch command: ${pf.id}: ${pf.command.str}")
                try {
                  val t0 = System.currentTimeMillis()
                  try {
                    pf.command.run
                  } finally {
                    val cmdTimeMs = System.currentTimeMillis() - t0
                    if (cmdTimeMs > (pf.command.timeoutSec * 1000) * 0.75){ // more than 75% of timeout time
                      log.warn(s"SMGUpdateActor: slow pre_fetch command: ${pf.id}: ${pf.command.str} " +
                        s"(took=${cmdTimeMs/1000}, timeout=${pf.command.timeoutSec})")
                    }
                    commandExecutionTimes(pf.id) = cmdTimeMs
                  }
                  //this is reached only on successfull pre-fetch
                  configSvc.sendPfMsg(SMGDFPfMsg(SMGRrd.tssNow, pf.id, interval, leafObjs, 0, List(), None))
                  if (updateCounters)
                    SMGStagedRunCounter.incIntervalCount(interval)
                  val updTs = if (pf.ignoreTs) None else Some(SMGRrd.tssNow)
                  val (childObjTrees, childPfTrees) = fRoot.children.partition(_.node.isRrdObj)
                  if (childObjTrees.nonEmpty) {
                    val childObjSeq = childObjTrees.map(_.node.asInstanceOf[SMGRrdObject])
                    childObjSeq.foreach { rrdObj =>
                      savedSelf ! SMGUpdateObjectMessage(rrdObj, updTs, updateCounters)
                    }
                    log.debug(s"SMGUpdateActor.runPrefetched($interval): Sent update messages for " +
                      s"[${pf.id}] object children (${childObjSeq.size})")
                  }
                  if (childPfTrees.nonEmpty) {
                    savedSelf ! SMGUpdateFetchMessage(interval, childPfTrees, updTs, pf.childConc, updateCounters)
                    log.debug(s"SMGUpdateActor.runPrefetched($interval): Sent update messages for " +
                      s"[${pf.id}] pre_fetch children (${childPfTrees.size})")
                  }
                } catch {
                  case ex: SMGCmdException => {
                    log.error(s"SMGUpdateActor: Failed pre_fetch command [${pf.id}]: ${ex.getMessage}")
                    val errTs = SMGRrd.tssNow
                    val errLst = List(pf.command.str + s" (${pf.command.timeoutSec})", ex.stdout, ex.stderr)
                    configSvc.sendPfMsg(SMGDFPfMsg(errTs, pf.id, interval, leafObjs, ex.exitCode, errLst, None))
                    if (updateCounters) {
                      fRoot.allNodes.foreach { cmd =>
                        SMGStagedRunCounter.incIntervalCount(interval)
                        if (cmd.isRrdObj) {
                          cmd.asInstanceOf[SMGObjectUpdate].invalidateCachedValues()
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
            }  // not a rrd obj

            val deltaT = SMGRrd.tssNow - t0
            lazy val tooSlowMsg = s"SMGUpdateActor: pre_fetch child sequence is taking more than $deltaT seconds. " +
              s"Current node=${fRoot.node.id}, childConc=$childConc"
            if (!slowErrLogged) {
              if (deltaT >= (interval * 3) / 4) {
                log.error(tooSlowMsg) // TODO for now just logging the condition
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
  }
}

object SMGUpdateActor {

 // val SLOW_UPDATE_THRESH = 600

  def ecForInterval(interval: Int): ExecutionContext = ExecutionContexts.ctxForInterval(interval)

//  def props = Props[SMGUpdateActor]
  case class SMGUpdateObjectMessage(
                                     obj: SMGObjectUpdate,
                                     ts: Option[Int],
                                     updateCounters: Boolean
                                   )

  case class SMGUpdateFetchMessage(
                                    interval:Int,
                                    rootCommands:Seq[SMGFetchCommandTree],
                                    ts: Option[Int],
                                    childConc: Int,
                                    updateCounters: Boolean
                                  )

  /**
    * Use this to do rrd updates and send appropriate object monitoring messages.
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
        processRrdUpdate(rrd, smgConfSvc, ts, values, log)
      } catch {
        case cex: SMGCmdException => {
          obj.invalidateCachedValues()
          log.error(s"SMGUpdateActor: Failed fetch command [${obj.id}]: ${cex.getMessage}")
          smgConfSvc.sendObjMsg(
            SMGDFObjMsg(ts.getOrElse(SMGRrd.tssNow), obj, List(), cex.exitCode,
              List(cex.cmdStr + s" (${cex.timeoutSec})", cex.stdout, cex.stderr))
          )
        }
        case fex: SMGFetchException => {
          obj.invalidateCachedValues()
          log.error(s"SMGUpdateActor: Fetch exception from  [${obj.id}]: ${fex.getMessage}")
          smgConfSvc.sendObjMsg(SMGDFObjMsg(SMGRrd.tssNow, obj, List(), -1, List("fetch_error", fex.getMessage)))
        }
        case ex: Throwable => {
          obj.invalidateCachedValues()
          log.ex(ex, s"SMGUpdateActor: Unexpected exception from fetch [${obj.id}]: ${ex.getMessage}")
          smgConfSvc.sendObjMsg(SMGDFObjMsg(SMGRrd.tssNow, obj, List(), -1, List("unexpected_error.", ex.getMessage)))
        }
      }
    } catch {
      case ex: Throwable => {
        obj.invalidateCachedValues()
        log.ex(ex, s"SMGUpdateActor got an unexpected while checking ${obj.id} rrd file")
        smgConfSvc.sendObjMsg(
          SMGDFObjMsg(ts.getOrElse(SMGRrd.tssNow), obj, List(), -1, List("unexpected_rrd_error", ex.getMessage))
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
      rrd.updateValues(values, ts)
      smgConfSvc.sendObjMsg(
        SMGDFObjMsg(ts.getOrElse(SMGRrd.tssNow), rrd.obju, values, 0, List())
      )
    } catch {
      case cex: SMGCmdException => {
        rrd.obju.invalidateCachedValues()
        log.error(s"SMGUpdateActor: Exception in update: [${rrd.obju.id}]: ${cex.toString}")
        smgConfSvc.sendObjMsg(
          SMGDFObjMsg(SMGRrd.tssNow, rrd.obju, List(), -1, List("update_error", cex.getMessage))
        )
      }
      case t: Throwable => {
        rrd.obju.invalidateCachedValues()
        log.ex(t, s"SMGUpdateActor: Unexpected exception in update: [${rrd.obju.id}]: ${t.toString}")
        smgConfSvc.sendObjMsg(
          SMGDFObjMsg(SMGRrd.tssNow, rrd.obju, List(), -1, List("unexpected_update_error", t.getMessage))
        )
      }
    }
  }
}
