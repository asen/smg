package com.smule.smgplugins.syslog.server

import akka.actor.{Actor, ActorRef, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString
import com.smule.smgplugins.syslog.config.SyslogServerConfig
import com.smule.smgplugins.syslog.server.SyslogActorMessages.{ClientConnectedMsg, ClientDisconnectedMsg}
import com.smule.smgplugins.syslog.server.TcpServerActor.ConnectionClosedMsg
import play.api.Logger

import java.nio.ByteBuffer
import java.nio.charset.{Charset, CharsetDecoder, CodingErrorAction}

class SyslogConnectionActor(
                             conf: SyslogServerConfig,
                             tcpServer: ActorRef,
                             //connectionActor: ActorRef,
                             parserActors: Seq[ActorRef],
                             val connectionId: String,
                             syslogStatusActor: ActorRef) extends Actor {
  import Tcp._

  private val log = Logger.logger

  //context.watch(connectionActor)

  private val LINE_SEP = "\n"
  //private val LINE_FEED = "\r"
  private val PRIVAL_OB = '<'
  private val PRIVAL_CB = '>'

  private val buff: StringBuilder = new StringBuilder(32768)
  private var newLines: Long = 0

  private val parsersArray = parserActors.toArray
  private var nextParserIndex = 0
  def parserActor: ActorRef = {
    if (parsersArray.length > 1){
      val ret = nextParserIndex
      nextParserIndex += 1
      if (nextParserIndex >= parsersArray.length){
        nextParserIndex = 0
      }
      parsersArray(ret)
    } else parsersArray(0)
  }

  private def ixofPayload: Int = if (buff.isEmpty || (buff(0) != PRIVAL_OB)) 0 else {
    val ixofCb = buff.indexOf(PRIVAL_CB)
    if (ixofCb >= 0) {
      ixofCb + 1
    } else 0
  }

  protected def sendStatsIfNeeded(): Unit = {
    if (newLines >= conf.statsUpdateNumLines){
      SyslogServerStatusActor.updateConnectionLines(syslogStatusActor, newLines = newLines)
      newLines = 0
    }
  }

  private def processLines(): Unit = {
    var ixofn = buff.indexOf(LINE_SEP)
    while (ixofn > 0) {
      val ln = buff.substring(ixofPayload, ixofn)
      buff.delete(0, ixofn + 1)
      ixofn = buff.indexOf(LINE_SEP)
      newLines += 1
      parserActor ! ln //.stripSuffix(LINE_FEED)
    }
    sendStatsIfNeeded()
  }

  private val safeByteDecoder: CharsetDecoder = Charset.forName("UTF-8").newDecoder
  safeByteDecoder.onMalformedInput(CodingErrorAction.REPLACE)
  safeByteDecoder.onUnmappableCharacter(CodingErrorAction.REPLACE)

  private def safeDecodeBytes(b: Array[Byte]): String = {
    safeByteDecoder.decode(ByteBuffer.wrap(b)).toString
  }

  private def dataToStr(data: ByteString): String = {
    try {
      data.utf8String
    } catch {
      case t: Throwable => { // this is potentially slower so only use on exception
        val ret = safeDecodeBytes(data.toArray)
        log.warn(s"SyslogConnectionActor($connectionId): Bad utf8String detected (${t.getMessage}): $ret")
        ret
      }
    }
  }

  syslogStatusActor ! ClientConnectedMsg(connectionId)

  private def flush(): Unit = {
    processLines()
    if (buff.nonEmpty) {
      parserActor ! buff.substring(ixofPayload)
      buff.clear()
    }
  }

  override def receive: Receive = {
    case Received(data) => {
      buff.append(dataToStr(data))
      processLines()
    }
    case PeerClosed => {
      flush()
      syslogStatusActor ! ClientDisconnectedMsg(connectionId)
      tcpServer ! ConnectionClosedMsg(connectionId)
      log.info(s"SyslogConnectionActor.receive: PeerClosed: $connectionId")
      context.stop(self)
    }
    case Closed =>
      log.info(s"SyslogConnectionActor.receive: Closed: $connectionId")
      flush()
      tcpServer ! ConnectionClosedMsg(connectionId)
    case ConfirmedClosed =>
      log.info(s"SyslogConnectionActor.receive: ConfirmedClosed: $connectionId")
      flush()
      tcpServer ! ConnectionClosedMsg(connectionId)
    case Terminated(subj) =>
      log.info(s"SyslogConnectionActor.receive: Terminated($subj): $connectionId")
      flush()
    //tcpServer ! ConnectionClosedMsg(connectionId)
    case x => log.warn(s"SyslogConnectionActor: unexpected message: ${x}")
  }
}

object SyslogConnectionActor {

  def props(conf: SyslogServerConfig ,tcpServer: ActorRef,
            parserActors: Seq[ActorRef], connectionId: String, syslogStatusActor: ActorRef): Props =
    Props(new SyslogConnectionActor(conf, tcpServer, parserActors, connectionId, syslogStatusActor))
}

