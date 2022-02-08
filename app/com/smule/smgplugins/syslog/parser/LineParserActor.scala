package com.smule.smgplugins.syslog.parser

import akka.actor.{ActorRef, Props}
import com.smule.smgplugins.syslog.config.LineSchema
import com.smule.smgplugins.syslog.shared.{LineData, LineParser}

class LineParserActor(override val serverId: String,
                      override val connectionId: String,
                      override val lineParser: LineParser,
                      override val syslogStatusActor: ActorRef,
                      val schema: LineSchema,
                      consumers: Seq[ActorRef]) extends BaseLineProcessingActor {

  log.debug(s"LineParserActor created with ${consumers.size} consumers. serverId=$serverId connectionId=$connectionId") // each connection has one of those

  private def isIgnored(pln: LineData): Boolean = {
    // TODO
    false
  }

  override protected def myProcessLine(ln: String): Unit = {
    val pln = lineParser.parseData(ln)
    if (pln.isDefined){
      lastHitTs = pln.get.tsms
      numHits += 1
      if (!isIgnored(pln.get)){
        consumers.foreach { c =>
          c ! pln.get
          forwardedHits += 1
        }
      }
    } else if (schema.logFailedToParse)
      log.warn(s"PARSE_ERROR($serverId): " + ln)
  }
}

object LineParserActor {
  def props(serverId: String,
            connectionId: String,
            lineParser: LineParser,
            syslogStatusActor: ActorRef,
            schema: LineSchema,
            consumers: Seq[ActorRef]): Props =
    Props(new LineParserActor(serverId, connectionId, lineParser, syslogStatusActor,
      schema, consumers))
}

