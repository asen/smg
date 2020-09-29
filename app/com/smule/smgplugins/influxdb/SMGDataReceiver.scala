package com.smule.smgplugins.influxdb

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGDataFeedListener, SMGDataFeedMsgCmd, SMGDataFeedMsgRun, SMGDataFeedMsgVals}
import com.smule.smg.plugin.SMGPluginLogger
import com.smule.smg.rrd.SMGRrd

class SMGDataReceiver(confParser: SMGInfluxDbPluginConfParser,
                      smgConfSvc: SMGConfigService,
                      log: SMGPluginLogger) extends SMGDataFeedListener{
  private def conf = confParser.conf

  private val actorReceiver = smgConfSvc.actorSystem.
    actorOf(InfluxDbWriterActor.props(log, confParser))

  private val GROUP_INDEX_SUFFIX_REGEX = "\\._\\d+$".r

  override def receiveValuesMsg(msg: SMGDataFeedMsgVals): Unit = {
    if (!conf.writesEnabled)
      return
    val uid = if (conf.stripGroupIndexSuffix && msg.obj.labels.nonEmpty &&
                  GROUP_INDEX_SUFFIX_REGEX.findFirstIn(msg.obj.id).nonEmpty)
      GROUP_INDEX_SUFFIX_REGEX.replaceAllIn(msg.obj.id, "")
    else
      msg.obj.id
    val dbRecs: List[InfluxDbRecord] = msg.data.values.zip(msg.obj.vars).map { case (value, varMap) =>
      InfluxDbRecord(
        uid = uid,
        tags = (msg.obj.labels ++ varMap).toSeq,
        value = value,
        ts = msg.data.ts.getOrElse(SMGRrd.tssNow).toLong
      )
    }
    InfluxDbWriterActor.addRecords(actorReceiver, dbRecs)
  }

  override def receiveRunMsg(msg: SMGDataFeedMsgRun): Unit = {
    if (!conf.writesEnabled)
      return
    if (!msg.isOverlap)
      flush()
  }

  override def receiveCommandMsg(msg: SMGDataFeedMsgCmd): Unit = {
//    log.info("SMGDataReceiver.receivePfMsg: " + msg)
  }

  def flush(): Unit = {
    InfluxDbWriterActor.flushRecords(actorReceiver)
  }
}
