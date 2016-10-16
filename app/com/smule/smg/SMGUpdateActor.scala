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
    case SMGUpdateMessage(rrdConf:SMGRrdConfig, obj:SMGObjectUpdate, ts: Option[Int]) => {
      log.debug("SMGUpdateActor received SMGUpdateMessage for " + obj)
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
    case SMGUpdatePreFetchedMessage(rrdConf:SMGRrdConfig, pf:SMGPreFetchCmd, objs: Seq[SMGObjectUpdate]) => {
      log.debug("SMGUpdateActor received SMGUpdatePreFetchedMessage for " + objs.size + " objects. pre_fetch=" + pf )
      if (objs.nonEmpty) {
        Future {
          try {
            log.debug("Running pre_fetch command: " + pf)
            try {
              pf.cmd.run
              configSvc.sendPfMsg(SMGDFPfMsg(SMGRrd.tssNow, pf.id, objs, 0, List()))
            } catch {
              case ex: SMGCmdException => {
                log.debug("Failed pre_fetch command: " + pf)
                val errTs = SMGRrd.tssNow
                val errLst = List(pf.cmd.str + s" (${pf.cmd.timeoutSec})", ex.stdout, ex.stderr)
                configSvc.sendPfMsg(SMGDFPfMsg(errTs, pf.id, objs, ex.exitCode, errLst))
                objs.foreach { o =>
                  SMGRunStats.incIntervalCount(o.interval)
                  configSvc.sendObjMsg(SMGDFObjMsg(errTs, o, List(), ex.exitCode, errLst))
                }
                throw ex
              }
            }
            //this is reached only on successfull pre-fetch
            val updTs = SMGRrd.tssNow
            objs.foreach { obj =>
              self ! SMGUpdateMessage(rrdConf, obj, Some(updTs))
              log.debug("SMGUpdateActor.runPrefetched(" + obj.interval + "): Sent update message for: " + obj.id)
            }
          } catch {
            case ex: Throwable => {
              log.error("Exception in pre_fetch: [" + pf.id + "]: " + ex.toString)
            }
          }
        }(ecForInterval(objs.head.interval))
      } else {
        log.warn("SMGUpdatePreFetchedMessage: empty object sequence provided, ignoring. pf=" + pf)
      }
    }
  }
}

object SMGUpdateActor {

  val SLOW_UPDATE_THRESH = 600

  def ecForInterval(interval: Int) = ExecutionContexts.ctxForInterval(interval)

//  def props = Props[SMGUpdateActor]
  case class SMGUpdateMessage(rrdConf:SMGRrdConfig, obj: SMGObjectUpdate, ts: Option[Int])
  case class SMGUpdatePreFetchedMessage(rrdConf:SMGRrdConfig, pf:SMGPreFetchCmd, objs: Seq[SMGObjectUpdate])
}
