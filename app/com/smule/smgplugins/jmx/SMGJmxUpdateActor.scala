package com.smule.smgplugins.jmx


import akka.actor.Actor
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGDataFeedMsgCmd, SMGRunStats, SMGUpdateActor}
import com.smule.smg.monitor.SMGState
import com.smule.smg.plugin.SMGPlugin
import com.smule.smg.rrd.{SMGRrd, SMGRrdUpdateData}

import scala.concurrent.{ExecutionContext, Future}

/**
  * An actor responsible for doing rrd fetches/updates.
  */
class SMGJmxUpdateActor(
                         smgConfSvc: SMGConfigService,
                         plugin: SMGPlugin,
                         confParser: SMGJmxConfigParser,
                         jmxClient: SMGJmxClient,
                         runCounterName: String,
                         onComplete: () => Unit
                       ) extends Actor {
  import SMGJmxUpdateActor._

  private val log = jmxClient.log

  private val myUpdateEc: ExecutionContext = smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.jmx-plugin")

  private def incrementCounter() : Unit = {
    if (SMGRunStats.incCustomCounter(runCounterName)) {
      onComplete()
    }
  }

  override def receive: Receive = {
    case SMGJmxUpdateMessage(hostPort: String, objs: List[SMGJmxObject]) => {
      log.debug(s"SMGUpdateActor received SMGJmxUpdateMessage for ${objs.map(_.id)}")
      Future {
        try {
          val errOpt = jmxClient.checkJmxConnection(hostPort)
          lazy val pfId = objs.head.baseId
          if (errOpt.isDefined) {
            smgConfSvc.sendCommandMsg(SMGDataFeedMsgCmd(SMGState.tssNow,
              pfId, plugin.interval, objs, -1,
              List(errOpt.get), Some(plugin.pluginId)))
            objs.foreach { obj => incrementCounter() }
          } else {
            smgConfSvc.sendCommandMsg(SMGDataFeedMsgCmd(SMGState.tssNow,
              pfId, plugin.interval, objs, 0,
              List(), Some(plugin.pluginId)))
            objs.foreach { obj =>
              try {
                val lst = try {
                  Some(jmxClient.fetchJmxValues(hostPort, obj.jmxName, obj.attrs))
                } catch {
                  case t: Throwable =>
                    smgConfSvc.invalidateCachedValues(obj)
                    log.error(s"SMGJmxUpdateActor: Fetch exception from  [${obj.id}]: ${t.getMessage}")
                    smgConfSvc.sendCommandMsg(SMGDataFeedMsgCmd(SMGRrd.tssNow, obj.id, obj.interval,
                      Seq(obj), -1, List("fetch_error", t.getMessage), Some(plugin.pluginId)))
                    None
                }
                if (lst.isDefined) {
                  smgConfSvc.sendCommandMsg(SMGDataFeedMsgCmd(SMGState.tssNow,
                    obj.id, plugin.interval, objs, 0,
                    List(), Some(plugin.pluginId)))
                  SMGUpdateActor.processObjectUpdate(obj, smgConfSvc, None,
                    SMGRrdUpdateData(lst.get, Some(SMGRrd.tssNow)), log)
                }
              } finally {
                incrementCounter()
              }
            }
          }
        } catch { case t: Throwable =>
          log.ex(t, s"Unexpected exception in SMGJmxUpdateActor.receive Future: ${t.getMessage}")
        }
      }(myUpdateEc)
    }
  }
}

object SMGJmxUpdateActor {

  case class SMGJmxUpdateMessage(
                                  hostPort: String,
                                  objs: List[SMGJmxObject]
                                )

}
