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
        try {
          log.debug(s"SMGUpdateActor Future: updating ${obj.id}")
          val rrd = new SMGRrdUpdate(obj, configSvc)
          val t0 = System.currentTimeMillis()
          try {
            rrd.createOrUpdate(ts)
          } finally {
            commandExecutionTimes(obj.id) = System.currentTimeMillis() - t0
          }
        } catch {
          case ex: Throwable => {
            obj.invalidateCachedValues()
            log.ex(ex, s"SMGUpdateActor got an error while updating ${obj.id}")
          }
        } finally {
          if (updateCounters) {
            SMGStagedRunCounter.incIntervalCount(obj.interval)
          }
        }
      }(ecForInterval(obj.interval))
    }

    case SMGUpdateFetchMessage(interval:Int, fRoots: Seq[SMGFetchCommandTree],
                               ts: Option[Int], updateCounters: Boolean) => {
      log.debug(s"SMGUpdateActor received SMGUpdateFetchMessage for ${fRoots.size} commands")
      val savedSelf = self
      Future {
        fRoots.foreach { fRoot =>
          if (fRoot.node.isRrdObj) {
            savedSelf ! SMGUpdateObjectMessage(fRoot.node.asInstanceOf[SMGRrdObject], ts, updateCounters)
          } else {
            val pf = fRoot.node
            log.debug(s"SMGUpdateActor.SMGUpdateFetchMessage processing command for " +
              s"pre_fetch ${pf.id}, ${fRoot.size} commands")
            val leafObjs = fRoot.leafNodes.map { c => c.asInstanceOf[SMGRrdObject] }
            try {
              log.debug(s"Running pre_fetch command: ${pf.id}: ${pf.command.str}")
              try {
                val t0 = System.currentTimeMillis()
                try {
                  pf.command.run
                } finally {
                  commandExecutionTimes(pf.id) = System.currentTimeMillis() - t0
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
                  savedSelf ! SMGUpdateFetchMessage(interval, childPfTrees, updTs, updateCounters)
                  log.debug(s"SMGUpdateActor.runPrefetched($interval): Sent update messages for " +
                    s"[${pf.id}] pre_fetch children (${childPfTrees.size})")
                }

              } catch {
                case ex: SMGCmdException => {
                  log.error(s"Failed pre_fetch command [${pf.id}]: ${ex.getMessage}")
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
                log.ex(ex, "Unexpected exception in pre_fetch: [" + pf.id + "]: " + ex.toString)
              }
            }
          }
        }
      }(ecForInterval(interval))
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
                                    fRoots:Seq[SMGFetchCommandTree],
                                    ts: Option[Int],
                                    updateCounters: Boolean
                                  )
}
