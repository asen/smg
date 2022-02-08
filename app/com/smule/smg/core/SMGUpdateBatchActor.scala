package com.smule.smg.core

import akka.actor.{Actor, ActorRef, Props}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGUpdateBatchActor.{SMGFlushBatchMsg, SMGUpdateBatchMsg}
import com.smule.smg.rrd.{SMGRrd, SMGRrdConfig, SMGRrdUpdate, SMGRrdUpdateData}

import scala.collection.mutable.ListBuffer

class SMGUpdateBatchActor(configSvc: SMGConfigService) extends Actor{
  private val log = SMGLogger

  private val buf = ListBuffer[SMGUpdateBatchMsg]()

  private def rrdConf: SMGRrdConfig = configSvc.config.rrdConf
  private var processedCount = 0

  private def processUpdate(msg: SMGUpdateBatchMsg): Unit = {
    buf += msg
    if (buf.size >= rrdConf.rrdcachedUpdateBatchSize) flushBuf()
  }

  private def runSocatCommand(inputStr: String): (Boolean, List[String]) =
    SMGUpdateBatchActor.runSocatCommand(rrdConf, inputStr)

  private def flushBuf(reason: String = "None"): Unit = {
    if (buf.isEmpty)
      return
    if (rrdConf.rrdToolSocket.isEmpty) {
      log.error(s"SMGUpdateBatchActor.flushBuf($reason): called with empty rrdConf.rrdToolSocket")
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
    val (success, out) = runSocatCommand(updateStr)
    if (out.contains("0 errors")) {
      log.debug(s"SMGUpdateBatchActor.flushBuf($reason): success: old processedCount=$processedCount buf.size=${buf.size}")
    } else if (success && out.mkString("").trim() == ""){
      // TODO verify if this is a failure and needs retrying
      log.warn(s"SMGUpdateBatchActor.flushBuf($reason): empty output (exit=$success) old processedCount=$processedCount " +
        s"buf.size=${buf.size} buf.head.ou.id=${buf.head.ou.id}")
    } else if (!success) {
      log.error(s"SMGUpdateBatchActor.flushBuf($reason): " +
        s"failed (exit=$success), here is the input:\n${updateStr}")
      log.error(s"SMGUpdateBatchActor.flushBuf($reason): " +
        s"failed (exit=$success), here is the output:\n${out.mkString("\n")}")
    }
    processedCount += buf.size
    buf.clear()
  }

  private def flushAll(reason: String): Unit = {
    val (ret, out) = runSocatCommand("FLUSHALL\n")
    if (!ret){
      log.error(s"SMGUpdateBatchActor.flushAll($reason): " +
        s"failed, output:\n${out.mkString("\n")}")
    } else
      log.debug(s"SMGUpdateBatchActor.flushAll($reason): " +
        s"success, output:\n${out.mkString("\n")}")
  }

  override def receive: Receive = {
    case msg: SMGUpdateBatchMsg => processUpdate(msg)
    case SMGFlushBatchMsg(reason: String) => {
      flushBuf(reason)
      if (rrdConf.useBatchedUpdates) {
        val myFlushAll = rrdConf.rrdcachedFlushAllOnRun
        if (myFlushAll)
          flushAll(reason)
        log.debug(s"SMGUpdateBatchActor: processed flush($reason) message ($processedCount updates since last one) " +
          s"flushAll=$myFlushAll")
      }
      processedCount = 0
    }
    case x => log.error(s"SMGUpdateBatchActor: unexpected message: $x")
  }
}

object SMGUpdateBatchActor {
  private val log = SMGLogger

  case class SMGUpdateBatchMsg(ou: SMGObjectUpdate, data: SMGRrdUpdateData)
  case class SMGFlushBatchMsg(reason: String)

  def props(configSvc: SMGConfigService): Props = Props(new SMGUpdateBatchActor(configSvc))

  def sendUpdate(aref: ActorRef, ou: SMGObjectUpdate, data: SMGRrdUpdateData): Unit = {
    aref ! SMGUpdateBatchMsg(ou, data)
  }

  def sendFlush(aref: ActorRef, reason: String): Unit = {
    aref ! SMGFlushBatchMsg(reason)
  }

  def runSocatCommand(rrdConf: SMGRrdConfig, inputStr: String): (Boolean, List[String]) ={
    val flushCommandTimeout = 10 // seconds TODO; read from conf
    try {
      val out = SMGCmd.runCommand(s"${rrdConf.rrdcachedSocatCommand} - ${rrdConf.rrdToolSocket.get}",
        flushCommandTimeout, Map(), Some(inputStr))
      (true, out)
    } catch {
      case t: Throwable => {
        log.ex(t, s"runSocatCommand error: ${t.getMessage}")
        (false, List())
      }
    }
  }

  def flushRrdFile(rrdConf: SMGRrdConfig, fname: String): Unit = {
    flushRrdFiles(rrdConf, Seq(fname))
  }

  def flushRrdFiles(rrdConf: SMGRrdConfig, fnames: Seq[String]): Unit = {
    if (fnames.isEmpty)
      return
    val ins = fnames.map(s => s"FLUSH $s\n").mkString
    runSocatCommand(rrdConf, ins)
  }
}
