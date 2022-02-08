package com.smule.smgplugins.syslog.server

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.smule.smgplugins.syslog.config.SyslogServerConfig
import com.smule.smgplugins.syslog.server.SyslogActorMessages._
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.{ExecutionContext, Future}

class SyslogServerStatusActor(conf: SyslogServerConfig) extends Actor {
  private val log = Logger.logger

  private var totalLines: Long = 0L
  private var mergedLines: Long = 0L
  private var totalHits: Long = 0L
  private var forwardedHits: Long = 0L
  private var lastHitTs: Long = 0L

  private val activeClients = mutable.Set[String]()
  private var totalClients = 0L

  def onClientConnected(clientAddrStr: String): Unit = {
    totalClients += 1
    activeClients += clientAddrStr
  }

  def onClientDisconnected(clientAddrStr: String): Unit = {
    activeClients -= clientAddrStr
  }

  private def getServerStatus: SyslogServerStatus = {
    SyslogServerStatus(
      serverId = conf.serverId,
      listenPort = conf.bindPort,
      activeClients = activeClients.toSeq,
      totalClients = totalClients,
      totalLines = totalLines,
      mergedLines = mergedLines,
      totalHits = totalHits,
      forwardedHits = forwardedHits,
      lastHitTs = lastHitTs
    )
  }

  override def receive: Receive = {
    case ClientConnectedMsg(clientAddrStr) => onClientConnected(clientAddrStr)
    case ClientDisconnectedMsg(clientAddrStr) => onClientDisconnected(clientAddrStr)
    case UpdateLinesMsg(newLines: Long) => totalLines += newLines
    case UpdateConnectionStatsMsg(
    clientAddrStr: String,
    newMergedLines,
    newHits: Long,
    newForwardedHits: Long,
    newLastHitTs: Long
    ) =>  {
      mergedLines += newMergedLines
      totalHits += newHits
      forwardedHits += newForwardedHits
      if (newLastHitTs > lastHitTs) lastHitTs = newLastHitTs
    }
    case GetStatusMsg() => sender() ! getServerStatus
    case LogStatusMsg() => log.info("SERVER_STATUS: " + getServerStatus.inspect(includePort = true, skipClients = true))
    case x => {
      log.error(s"SyslogServerStatusActor(${conf.serverId}).receive: unexpected message: $x")
    }
  }
}

object SyslogServerStatusActor {
  def props(conf: SyslogServerConfig): Props =
    Props(new SyslogServerStatusActor(conf))

  def updateConnectionStats(actorRef: ActorRef,
                            clientAddrStr: String,
                            newMergedLines: Long,
                            newHits: Long,
                            newForwardedHits: Long,
                            newLastHitTs: Long): Unit = {
    actorRef ! UpdateConnectionStatsMsg(
      clientAddrStr = clientAddrStr,
      newMergedLines = newMergedLines,
      newHits = newHits,
      newForwardedHits = newForwardedHits,
      newLastHitTs = newLastHitTs
    )
  }

  def updateConnectionLines(actorRef: ActorRef,
                            newLines: Long
                           ): Unit = {
    actorRef ! UpdateLinesMsg(newLines)
  }

  def getStatus(actorRef: ActorRef, ec: ExecutionContext): Future[SyslogServerStatus] = {
    implicit val tmt : Timeout = Timeout(60000, MILLISECONDS)
    (actorRef ? GetStatusMsg()).map(_.asInstanceOf[SyslogServerStatus])(ec)
  }
}
