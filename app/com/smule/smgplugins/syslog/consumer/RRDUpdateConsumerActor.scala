package com.smule.smgplugins.syslog.consumer

import akka.actor.{Actor, ActorRef, Props}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGLoggerApi, SMGUpdateActor}
import com.smule.smg.rrd.SMGRrdUpdateData
import com.smule.smgplugins.syslog.consumer.RRDUpdateConsumerActor.Tick
import com.smule.smgplugins.syslog.shared.{LineData, SyslogObjectsCache}

class RRDUpdateConsumerActor(
                              cfSvc: SMGConfigService,
                              objectsCache: SyslogObjectsCache,
                              log: SMGLoggerApi
                            ) extends Actor {

  private def tick(): Unit = {
    objectsCache.checkCapacity()
  }

  private def processData(dl: LineData): Unit = {
    val ou = objectsCache.getOrCreateObject(dl)
    val tss = (dl.tsms / 1000).toInt
    val udata = SMGRrdUpdateData(dl.values, Some(tss))
    SMGUpdateActor.processObjectUpdate(obj = ou, smgConfSvc = cfSvc,
      ts = Some(tss), res = udata, log = log)
  }

  override def receive: Receive = {
    case dl: LineData => processData(dl)
    case Tick() => tick()
    case x => {
      log.error(s"${this.getClass.getName}().receive: unexpected message: $x")
    }
  }
}

object RRDUpdateConsumerActor {

  case class Tick()

  def props(
             cfSvc: SMGConfigService,
             objectsCache: SyslogObjectsCache,
             log: SMGLoggerApi
           ): Props =
    Props(new RRDUpdateConsumerActor(cfSvc, objectsCache, log))
  def tick(aref: ActorRef): Unit = aref ! Tick()
}