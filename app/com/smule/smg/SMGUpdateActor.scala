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
    case SMGUpdateObjectMessage(rrdConf:SMGRrdConfig, obj:SMGObjectUpdate, ts: Option[Int]) => {
      log.debug("SMGUpdateActor received SMGUpdateObjectMessage for " + obj)
      Future {
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
      }(ecForInterval(obj.interval))
    }
    case SMGUpdateFetchMessage(rrdConf:SMGRrdConfig, interval:Int, fRoot:SMGFetchCommandTree, ts: Option[Int]) => {
      log.debug("SMGUpdateActor received SMGUpdateFetchMessage for " + fRoot.size + " commands. pre_fetch=" + fRoot.node )
      if (fRoot.node.isRrdObj) {
        self ! SMGUpdateObjectMessage(rrdConf, fRoot.node.asInstanceOf[SMGRrdObject], ts)
      } else {
        Future {
          val pf = fRoot.node
          val leafObjs = fRoot.leafNodes.map{ c => c.asInstanceOf[SMGRrdObject] }
          try {
            log.debug(s"Running pre_fetch command: $pf")
            try {
              pf.command.run
              configSvc.sendPfMsg(SMGDFPfMsg(SMGRrd.tssNow, pf.id, leafObjs, 0, List()))
              SMGRunStats.incIntervalCount(interval)
              //this is reached only on successfull pre-fetch
              val updTs = SMGRrd.tssNow
              fRoot.children.foreach { t =>
                self ! SMGUpdateFetchMessage(rrdConf, interval, t, Some(updTs))
                log.debug(s"SMGUpdateActor.runPrefetched($interval): Sent update message for: ${fRoot.node.id}")
              }
            } catch {
              case ex: SMGCmdException => {
                log.debug("Failed pre_fetch command: " + pf)
                val errTs = SMGRrd.tssNow
                val errLst = List(pf.command.str + s" (${pf.command.timeoutSec})", ex.stdout, ex.stderr)
                configSvc.sendPfMsg(SMGDFPfMsg(errTs, pf.id, leafObjs, ex.exitCode, errLst))
                leafObjs.foreach { o =>
                  configSvc.sendObjMsg(SMGDFObjMsg(errTs, o, List(), ex.exitCode, errLst))
                }
                (1 to fRoot.size).foreach(_ => SMGRunStats.incIntervalCount(interval))
                throw ex
              }
            }
          } catch {
            case ex: Throwable => {
              log.error("Exception in pre_fetch: [" + pf.id + "]: " + ex.toString)
            }
          }
        }(ecForInterval(interval))
      }
    }
  }
}

object SMGUpdateActor {

  val SLOW_UPDATE_THRESH = 600

  def ecForInterval(interval: Int) = ExecutionContexts.ctxForInterval(interval)

//  def props = Props[SMGUpdateActor]
  case class SMGUpdateObjectMessage(rrdConf:SMGRrdConfig, obj: SMGObjectUpdate, ts: Option[Int])
  case class SMGUpdateFetchMessage(rrdConf:SMGRrdConfig, interval:Int, fRoot:SMGFetchCommandTree, ts: Option[Int])
}
