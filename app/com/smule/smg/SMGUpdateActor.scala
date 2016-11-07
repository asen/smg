package com.smule.smg

import javax.inject.Inject

import akka.actor.{Actor, ActorSystem, Props}

import scala.concurrent.Future

/**
 * Created by asen on 10/23/15.
 */


/**
  * An actor responsible for doing rrd fetches/updates.
  */
class SMGUpdateActor(configSvc: SMGConfigService) extends Actor {
  import SMGUpdateActor._

  val log = SMGLogger

  override def receive: Receive = {
    case SMGUpdateObjectMessage(rrdConf:SMGRrdConfig, objs:Seq[SMGObjectUpdate], ts: Option[Int]) => {
      if (objs.isEmpty) {
        log.error("SMGUpdateActor received SMGUpdateObjectMessage for empty objects list")
      } else {
        log.debug("SMGUpdateActor received SMGUpdateObjectMessage for " + objs)
        Future {
          objs.foreach { obj =>
            try {
              log.debug("SMGUpdateActor Future: updating " + obj)
              // log.info("--- In the future ---")
              val rrd = new SMGRrdUpdate(rrdConf, obj, configSvc)
              rrd.createOrUpdate(ts)
            } catch {
              case ex: Throwable => {
                log.ex(ex, "SMGUpdateActor got an error while updating " + obj)
              }
            } finally {
              if (SMGRunStats.incIntervalCount(obj.interval)) {
                rrdConf.flushSocket()
              }
            }
          }
        }(ecForInterval(objs.head.interval))
      }
    }
    case SMGUpdateFetchMessage(rrdConf:SMGRrdConfig, interval:Int, fRoots: Seq[SMGFetchCommandTree], ts: Option[Int]) => {
      log.debug("SMGUpdateActor received SMGUpdateFetchMessage for " + fRoots.size + " commands")
      val savedSelf = self
      Future {
        fRoots.foreach { fRoot =>
          if (fRoot.node.isRrdObj) {
            savedSelf ! SMGUpdateObjectMessage(rrdConf, Seq(fRoot.node.asInstanceOf[SMGRrdObject]), ts)
          } else {
            val pf = fRoot.node
            log.debug(s"SMGUpdateActor.SMGUpdateFetchMessage processing command for pre_fetch ${pf.id}, ${fRoot.size} commands")
            val leafObjs = fRoot.leafNodes.map { c => c.asInstanceOf[SMGRrdObject] }
            try {
              log.debug(s"Running pre_fetch command: $pf")
              try {
                pf.command.run
                configSvc.sendPfMsg(SMGDFPfMsg(SMGRrd.tssNow, pf.id, leafObjs, 0, List()))
                SMGRunStats.incIntervalCount(interval)
                //this is reached only on successfull pre-fetch
                val updTs = if (pf.ignoreTs) None else Some(SMGRrd.tssNow)
                val (childObjTrees, childPfTrees) = fRoot.children.partition(_.node.isRrdObj)
                if (childObjTrees.nonEmpty) {
                  val childObjSeq = childObjTrees.map(_.node.asInstanceOf[SMGRrdObject])
                  childObjSeq.foreach { rrdObj =>
                    savedSelf ! SMGUpdateObjectMessage(rrdConf, Seq(rrdObj), updTs)
                  }
                  log.debug(s"SMGUpdateActor.runPrefetched($interval): Sent update messages for [${pf.id}] object children (${childObjSeq.size})")
                }
                if (childPfTrees.nonEmpty) {
                  savedSelf ! SMGUpdateFetchMessage(rrdConf, interval, childPfTrees, updTs)
                  log.debug(s"SMGUpdateActor.runPrefetched($interval): Sent update messages for [${pf.id}] pre_fetch children (${childPfTrees.size})")
                }

              } catch {
                case ex: SMGCmdException => {
                  log.error(s"Failed pre_fetch command [${pf.id}]: ${ex.getMessage}")
                  val errTs = SMGRrd.tssNow
                  val errLst = List(pf.command.str + s" (${pf.command.timeoutSec})", ex.stdout, ex.stderr)
                  configSvc.sendPfMsg(SMGDFPfMsg(errTs, pf.id, leafObjs, ex.exitCode, errLst))
                  leafObjs.foreach { o =>
                    configSvc.sendObjMsg(SMGDFObjMsg(errTs, o, List(), ex.exitCode, errLst))
                  }
                  (1 to fRoot.size).foreach(_ => SMGRunStats.incIntervalCount(interval))
                  //throw ex
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

  def ecForInterval(interval: Int) = ExecutionContexts.ctxForInterval(interval)

//  def props = Props[SMGUpdateActor]
  case class SMGUpdateObjectMessage(rrdConf:SMGRrdConfig, objs: Seq[SMGObjectUpdate], ts: Option[Int])
  case class SMGUpdateFetchMessage(rrdConf:SMGRrdConfig, interval:Int, fRoots:Seq[SMGFetchCommandTree], ts: Option[Int])
}
