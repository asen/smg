package com.smule.smgplugins.influxdb

import akka.actor.{Actor, ActorRef, Props}
import com.smule.smg.core.SMGLoggerApi
import com.smule.smgplugins.influxdb.InfluxDbWriterActor.{FlushRecords, WriteRecords}

import scala.collection.mutable.ListBuffer

class InfluxDbWriterActor(log: SMGLoggerApi,
                          confParser: SMGInfluxDbPluginConfParser) extends Actor {
  private def conf = confParser.conf
  private def dbConf = confParser.conf.dbConf.get // XXX this will throw if used when disabled

  private val dbApi = new InfluxDbApi(confParser, log)
  private val pending: ListBuffer[InfluxDbRecord] = ListBuffer[InfluxDbRecord]()
  private var recordsSinceLastFlushMsg: Int = 0


  private def addRecords(lst: List[InfluxDbRecord]): Unit = {
    pending ++= lst
    if (pending.size >= dbConf.writeBatchSize){
      flushRecords()
    }
  }

  private def flushRecords(): Unit = {
    val toWrite = pending.toList
    recordsSinceLastFlushMsg += toWrite.size
    dbApi.writeBatchAsync(toWrite)
    pending.clear()
  }

  override def receive: Receive = {
    case WriteRecords(lst: List[InfluxDbRecord]) => addRecords(lst)
    case FlushRecords() => {
      flushRecords()
      log.info(s"InfluxDbWriterActor - FlushRecords received - recordsSinceLastFlushMsg=$recordsSinceLastFlushMsg")
      recordsSinceLastFlushMsg = 0
    }
    case x => log.error(s"InfluxDbWriterActor.receive: Unexpected message received: $x")
  }
}

object InfluxDbWriterActor {
  def props(log: SMGLoggerApi, confParser: SMGInfluxDbPluginConfParser): Props =
    Props(new InfluxDbWriterActor(log, confParser))

  case class WriteRecords(lst: List[InfluxDbRecord])
  case class FlushRecords()

  def addRecords(aref: ActorRef, recs: List[InfluxDbRecord]): Unit = aref ! WriteRecords(recs)
  def flushRecords(aref: ActorRef): Unit = aref ! FlushRecords()
}
