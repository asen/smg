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
            try {
              val v = jmxClient.fetchJmxValues(hostPort, obj.jmxName, obj.attrs)
              try {
                obj.setCurrentValues(v)
                val rrd = new SMGRrdUpdate(obj, smgConfSvc)
                rrd.createOrUpdate(None)
              } catch {
                case ex: Throwable => {
                  log.ex(ex, s"Unexpected exception while updating rrd values: $hostPort, $obj")
                  smgConfSvc.sendObjMsg(SMGDFObjMsg(SMGRrd.tssNow, obj, List(), -1, List("RRD update error.", ex.getMessage)))
                }
              }
            } catch {
              case ex: Throwable => {
                log.ex(ex, s"Exception while fetching JMX values: $hostPort, ${obj.jmxName}:${obj.attrs}")
                smgConfSvc.sendObjMsg(SMGDFObjMsg(SMGRrd.tssNow, obj, List(), -1, List("JMX fetch error.", ex.getMessage)))
              }
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
