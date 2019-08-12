package com.smule.smg.core

import akka.actor.{Actor, ActorRef, Props}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGUpdateBatchActor.{SMGFlushBatchMsg, SMGUpdateBatchMsg}
import com.smule.smg.rrd.{SMGRrd, SMGRrdConfig, SMGRrdUpdate, SMGRrdUpdateData}

import scala.collection.mutable.ListBuffer

class SMGUpdateBatchActor(configSvc: SMGConfigService) extends Actor{
  private val log = SMGLogger

  private val buf = ListBuffer[SMGUpdateBatchMsg]()

  private val flushCommandTimeout = 10 // seconds TODO; read from conf
  private def rrdConf: SMGRrdConfig = configSvc.config.rrdConf
  private var processedCount = 0

  private def processUpdate(msg: SMGUpdateBatchMsg): Unit = {
    buf += msg
    if (buf.size >= rrdConf.rrdUpdateBatchSize) flush()
  }

  private def runFlushCommand(updateStr: String): (Boolean, List[String]) =
    try {
      val out = SMGCmd.runCommand(s"${rrdConf.rrdSocatCommand} - ${rrdConf.rrdToolSocket.get}",
        flushCommandTimeout, Map(), Some(updateStr))
      (true, out)
    } catch {
      case t: Throwable => (false, List())
    }
  private def flush(reason: String = "None"): Unit = {
    if (buf.isEmpty)
      return
    if (rrdConf.rrdToolSocket.isEmpty) {
      log.error(s"SMGUpdateBatchActor.flush($reason): called with empty rrdConf.rrdToolSocket")
      return
    }
    // TODO make this below async?
    val updateLines = ListBuffer[String]("BATCH")
    buf.foreach { msg =>
      val tss = SMGRrdUpdate.expandTs(msg.ou, msg.data.ts, nForNow = false)
      val vals = msg.data.values.map(d => SMGRrd.numRrdFormat(d, nanAsU = true)).mkString(":")
      updateLines += s"UPDATE ${msg.ou.rrdFile.get} ${tss}:${vals}"
    }
    updateLines += "."
    val updateStr = updateLines.mkString("\n") + "\n"
    val (success, out) = runFlushCommand(updateStr)
    if (out.contains("0 errors")) {
      log.debug(s"SMGUpdateBatchActor.flush($reason): success: old processedCount=$processedCount buf.size=${buf.size}")
    } else if (success && out.mkString("").strip() == ""){
      // TODO verify if this is a failure and needs retrying
      log.warn(s"SMGUpdateBatchActor.flush($reason): empty output (exit=$success) old processedCount=$processedCount " +
        s"buf.size=${buf.size} buf.head.ou.id=${buf.head.ou.id}")
    } else {
      log.error(s"SMGUpdateBatchActor.flush($reason): " +
        s"failed (exit=$success), here is the input:\n${updateStr}")
      log.error(s"SMGUpdateBatchActor.flush($reason): " +
        s"failed (exit=$success), here is the output:\n${out.mkString("\n")}")
    }
    processedCount += buf.size
    buf.clear()
  }

  override def receive: Receive = {
    case msg: SMGUpdateBatchMsg => processUpdate(msg)
    case SMGFlushBatchMsg(reason: String) => {
      flush(reason)
      if (rrdConf.useBatchedUpdates)
        log.info(s"SMGUpdateBatchActor: processed flush($reason) message ($processedCount updates since last flush message)")
      processedCount = 0
    }
    case x => log.error(s"SMGUpdateBatchActor: unexpected message: $x")
  }
}

object SMGUpdateBatchActor {
  case class SMGUpdateBatchMsg(ou: SMGObjectUpdate, data: SMGRrdUpdateData)
  case class SMGFlushBatchMsg(reason: String)

  def props(configSvc: SMGConfigService): Props = Props(classOf[SMGUpdateBatchActor], configSvc)

  def sendUpdate(aref: ActorRef, ou: SMGObjectUpdate, data: SMGRrdUpdateData): Unit = {
    aref ! SMGUpdateBatchMsg(ou, data)
  }

  def sendFlush(aref: ActorRef, reason: String): Unit = {
    aref ! SMGFlushBatchMsg(reason)
  }
}
