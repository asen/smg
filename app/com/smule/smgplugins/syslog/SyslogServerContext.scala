package com.smule.smgplugins.syslog

import akka.actor.{ActorRef, ActorSystem}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGLoggerApi, SMGObjectView}
import com.smule.smgplugins.syslog.config.PipelineConfig
import com.smule.smgplugins.syslog.consumer.RRDUpdateConsumerActor
import com.smule.smgplugins.syslog.parser.{GrokParser, LineParserActor}
import com.smule.smgplugins.syslog.server.{SyslogServerStatus, SyslogServerStatusActor, TcpServerActor}
import com.smule.smgplugins.syslog.shared.{LineParser, SyslogObjectsCache}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class SyslogServerContext(
                                smgConfSvc: SMGConfigService,
                                val conf: PipelineConfig,
                                log: SMGLoggerApi
                         ) {
  private val actorSystem: ActorSystem = smgConfSvc.actorSystem
  private val statusActor: ActorRef = actorSystem.actorOf(SyslogServerStatusActor.props(conf.syslogServerConfig))
  private val objectsCache: SyslogObjectsCache =
    new SyslogObjectsCache(conf.serverId, conf.smgObjectTemplate, maxCapacity = conf.lineSchema.maxCacheCapacity, log)
  //load cache from disk
  objectsCache.loadObjectsFromDisk()

  private val rrdUpdateConsumerActor: ActorRef =
    actorSystem.actorOf(RRDUpdateConsumerActor.props(smgConfSvc, objectsCache, log))

  private def parserActorsCreateFn(connectionId: String): Seq[ActorRef] = {
    1.to(conf.syslogServerConfig.numParsers).toSeq.map { ix =>
      val parser = new GrokParser(conf.lineSchema, log)
      actorSystem.actorOf(
        LineParserActor.props(serverId = conf.serverId, connectionId = connectionId, lineParser = parser,
          syslogStatusActor = statusActor, schema = conf.lineSchema,
          consumers = Seq(rrdUpdateConsumerActor)
        )
      )
    }
  }

  private val tcpServerActor: ActorRef = actorSystem.actorOf(TcpServerActor.props(
    conf = conf.syslogServerConfig,
    statusActor = statusActor,
    parserActorsCreateFn = parserActorsCreateFn
  ))

  def shutdown(ec: ExecutionContext): Unit =
    Await.result(TcpServerActor.shutdown(tcpServerActor, ec), Duration.Inf)

  def tick(): Unit = {
    RRDUpdateConsumerActor.tick(rrdUpdateConsumerActor)
  }

  def getStatus(ec: ExecutionContext): Future[SyslogServerStatus] = {
    SyslogServerStatusActor.getStatus(statusActor, ec)
  }

  def getObjectViews: Seq[SMGObjectView] = objectsCache.getObjectViews
  def cacheIsDirty:Boolean = objectsCache.isDirty
}
