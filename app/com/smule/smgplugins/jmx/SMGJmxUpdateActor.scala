package com.smule.smgplugins.jmx


import akka.actor.Actor
import play.libs.Akka
import com.smule.smg._

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
        if (errOpt.isDefined) {
          smgConfSvc.sendPfMsg(SMGDFPfMsg(SMGState.tssNow,
            confParser.hostPortPfId(hostPort), plugin.interval, objs, -1,
            List(errOpt.get), Some(plugin.pluginId)))
          objs.foreach { obj => incrementCounter() }
        } else {
          smgConfSvc.sendPfMsg(SMGDFPfMsg(SMGState.tssNow,
            confParser.hostPortPfId(hostPort), plugin.interval, objs, 0,
            List(), Some(plugin.pluginId)))
          objs.foreach { obj =>

            def fetchFn(): List[Double] = {
              try {
                val values = jmxClient.fetchJmxValues(hostPort, obj.jmxName, obj.attrs)
                obj.setCurrentValues(values)
                values
              } catch {
                case ex: Throwable => {
                  throw new SMGFetchException(s"JMX fetch error: $hostPort, ${obj.jmxName}:${obj.attrs}, msg=${ex.getMessage}")
                }
              }
            }

            try {
              SMGUpdateActor.processObjectUpdate(obj, smgConfSvc, None, fetchFn, log)
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
  val myUpdateEc: ExecutionContext = Akka.system.dispatchers.lookup("akka-contexts.jmx-plugin")


  case class SMGJmxUpdateMessage(
                                  hostPort: String,
                                  objs: List[SMGJmxObject]
                                )


}
