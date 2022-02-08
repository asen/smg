package com.smule.smgplugins.syslog.server

import akka.Done
import akka.actor.{Actor, ActorRef, Props}
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.util.Timeout
import com.smule.smgplugins.syslog.config.SyslogServerConfig
import com.smule.smgplugins.syslog.server.TcpServerActor.{ConnectionClosedMsg, ShutdownMsg}
import play.api.Logger

import java.net.InetSocketAddress
import scala.collection.mutable
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.{ExecutionContext, Future}

class TcpServerActor(conf: SyslogServerConfig,
                     statusActor: ActorRef,
                     parserActorsCreateFn: (String) => Seq[ActorRef]
                    ) extends Actor {

  import Tcp._
  import context.system

  private val log = Logger.logger

  private val logTag = s"(syslog:${conf.serverId}:${conf.bindPort})"

  private val manager = IO(Tcp)

  private val connectionActorsMap = mutable.Map[String, ActorRef]()

  manager ! Bind(self, new InetSocketAddress(conf.bindHost, conf.bindPort))

  private var socketActor: Option[ActorRef] = None
  private var shutdownRequestSender: Option[ActorRef] = None

  override def receive: Receive = {
    case b @ Bound(localAddress) =>
      socketActor = Some(sender())
      log.info(s"TcpServerActor$logTag.receive: Bound: ${localAddress.toString}")

    case CommandFailed(_: Bind) =>
      log.error(s"TcpServerActor$logTag.receive: CommandFailed: Bind: ${conf.serverId}:${conf.bindPort}")
      context stop self

    case c @ Connected(remote, local) =>
      val connectionId = remote.toString
      val parserActors = parserActorsCreateFn(connectionId)
      val connection = sender()
      val handler = context.actorOf(SyslogConnectionActor.props(conf, self, parserActors, connectionId, statusActor))
      connectionActorsMap.put(connectionId, connection)
      log.info(s"TcpServerActor$logTag.receive: Connected id=${conf.serverId}: " +
        s"got connection from ${remote.toString} to ${local.toString}")
      connection ! Register(handler)

    case Unbound =>
      log.info(s"TcpServerActor$logTag.receive: Unbound")
      if (connectionActorsMap.nonEmpty) {
        connectionActorsMap.values.foreach { aref =>
          aref ! Close
        }
      } else
        shutdownRequestSender.foreach(_ ! Done)

    case ConnectionClosedMsg(connectionId: String) =>
      connectionActorsMap.remove(connectionId)
      if (shutdownRequestSender.isDefined){
        if (connectionActorsMap.isEmpty){
          log.info(s"TcpServerActor$logTag.receive (shutdown): ConnectionClosedMsg($connectionId): " +
            s"All connections closed")
          shutdownRequestSender.foreach(_ ! Done)
        } else {
          log.info(s"TcpServerActor$logTag.receive (shutdown): ConnectionClosedMsg($connectionId): " +
            s"${connectionActorsMap.size} connections remaining")
        }
      } else
        log.debug(s"TcpServerActor$logTag.receive: ConnectionClosedMsg($connectionId), " +
          s"totalConnsAfter=${connectionActorsMap.size}")

    case ShutdownMsg() =>
      log.info(s"TcpServerActor$logTag.receive: Shutdown requested")
      shutdownRequestSender = Some(sender())
      socketActor.foreach(_ ! Unbind)

    case Closed =>
      log.info(s"TcpServerActor$logTag.receive: Closed received")
    case ConfirmedClosed =>
      log.info(s"TcpServerActor$logTag.receive: ConfirmedClosed received")

    case x => log.warn(s"TcpServerActor: unexpected message: ${x}")
  }
}

object TcpServerActor {
  case class ConnectionClosedMsg(connectionId: String)
  case class ShutdownMsg()

  def shutdown(actorRef: ActorRef, ec: ExecutionContext): Future[Done] = {
    implicit val tmt : Timeout = Timeout(60000, MILLISECONDS)
    (actorRef ? ShutdownMsg()).map(_.asInstanceOf[Done])(ec)
  }

  def props(conf: SyslogServerConfig,
            statusActor: ActorRef,
            parserActorsCreateFn: (String) => Seq[ActorRef]): Props =
    Props(new TcpServerActor(conf, statusActor, parserActorsCreateFn))
}