package com.smule.smgplugins.jmx


import akka.actor.Actor
import play.libs.Akka
import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGDataFeedMsgPf, SMGFetchException, SMGRunStats, SMGUpdateActor}
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
        val errOpt = jmxClient.checkJmxConnection(hostPort)
        lazy val pfId = objs.headOption.map(_.baseId).getOrElse("unexpected.jmx.missing.objects")
        if (errOpt.isDefined) {
          smgConfSvc.sendPfMsg(SMGDataFeedMsgPf(SMGState.tssNow,
            pfId, plugin.interval, objs, -1,
            List(errOpt.get), Some(plugin.pluginId)))
          objs.foreach { obj => incrementCounter() }
        } else {
          smgConfSvc.sendPfMsg(SMGDataFeedMsgPf(SMGState.tssNow,
            pfId, plugin.interval, objs, 0,
            List(), Some(plugin.pluginId)))
          objs.foreach { obj =>

            def fetchFn(): SMGRrdUpdateData = {
              val lst = try {
                jmxClient.fetchJmxValues(hostPort, obj.jmxName, obj.attrs)
              } catch {
                case ex: Throwable => {
                  throw new SMGFetchException(s"JMX fetch error: $hostPort, ${obj.jmxName}:${obj.attrs}, msg=${ex.getMessage}")
                }
              }
              SMGRrdUpdateData(lst, Some(SMGRrd.tssNow))
            }

            try {
              SMGUpdateActor.processObjectUpdate(obj, smgConfSvc, None, fetchFn _, log)
            } finally {
              incrementCounter()
            }
          }

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
