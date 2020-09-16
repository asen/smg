package com.smule.smgplugins.influxdb

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGDataFeedListener, SMGDataFeedMsgObj, SMGDataFeedMsgPf, SMGDataFeedMsgRun}
import com.smule.smg.plugin.SMGPluginLogger

class SMGDataReceiver(confParser: SMGInfluxDbPluginConfParser,
                      smgConfSvc: SMGConfigService,
                      log: SMGPluginLogger) extends SMGDataFeedListener{
  private def conf = confParser.conf

  private val actorReceiver = smgConfSvc.actorSystem.
    actorOf(InfluxDbWriterActor.props(log, confParser))

  private val GROUP_INDEX_SUFFIX_REGEX = "\\._\\d+$".r

  override def receiveObjMsg(msg: SMGDataFeedMsgObj): Unit = {
    if (!conf.writesEnabled)
      return
    val uid = if (conf.stripGroupIndexSuffix && msg.obj.labels.nonEmpty &&
                  GROUP_INDEX_SUFFIX_REGEX.findFirstIn(msg.obj.id).nonEmpty)
      GROUP_INDEX_SUFFIX_REGEX.replaceAllIn(msg.obj.id, "")
    else
      msg.obj.id
    val dbRecs: List[InfluxDbRecord] = msg.vals.zip(msg.obj.vars).map { case (value, varMap) =>
      InfluxDbRecord(
        uid = uid,
        tags = (msg.obj.labels ++ varMap).toSeq,
        value = value,
        ts = msg.ts
      )
    }
    InfluxDbWriterActor.addRecords(actorReceiver, dbRecs)
  }

  override def receiveRunMsg(msg: SMGDataFeedMsgRun): Unit = {
    if (!msg.isOverlap)
      flush()
  }

  override def receivePfMsg(msg: SMGDataFeedMsgPf): Unit = {
//    log.info("SMGDataReceiver.receivePfMsg: " + msg)
  }

  def flush(): Unit = {
    InfluxDbWriterActor.flushRecords(actorReceiver)
  }
}
