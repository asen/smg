package com.smule.smgplugins.syslog.parser

import akka.actor.{Actor, ActorRef}
import com.smule.smgplugins.syslog.server.SyslogServerStatusActor
import com.smule.smgplugins.syslog.shared.LineParser
import org.slf4j
import play.api.Logger

abstract class BaseLineProcessingActor extends Actor {

  val serverId: String
  val connectionId: String
  val lineParser: LineParser
  val syslogStatusActor: ActorRef
  def syslogStatsUpdateNumLines: Long = lineParser.schema.syslogStatsUpdateNumLines

  // actual line processing (after lineMerger)
  // should update numHits, forwardedHits and lastHitTs (if available)
  protected def myProcessLine(ln: String): Unit

  protected val log: slf4j.Logger = Logger.logger

  protected var numLines: Long = 0L
  protected var mergedLines: Long = 0L
  protected var numHits: Long = 0L
  protected var forwardedHits: Long = 0L
  protected var lastHitTs: Long = 0L

  protected var nextSendStatsNumLines: Long = syslogStatsUpdateNumLines

  protected def sendStatsIfNeeded(): Unit = {
    if (numLines >= nextSendStatsNumLines){
      val lhts = if (lastHitTs < 0) System.currentTimeMillis() else lastHitTs
      SyslogServerStatusActor.updateConnectionStats(syslogStatusActor, connectionId,
        newMergedLines = mergedLines, newHits = numHits,
        newForwardedHits = forwardedHits, newLastHitTs = lhts)
      numLines = 0
      mergedLines = 0
      numHits = 0
      forwardedHits = 0
      nextSendStatsNumLines = syslogStatsUpdateNumLines
    }
  }

  protected def processLine(ln:String): Unit = {
    numLines += 1
    myProcessLine(ln)
    mergedLines += 1
    sendStatsIfNeeded()
  }

  override def receive: Receive = {
    case ln: String => processLine(ln)
    case x => {
      log.error(s"${this.getClass.getName}($serverId,$connectionId).receive: unexpected message: $x")
    }
  }
}
