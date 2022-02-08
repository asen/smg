package com.smule.smgplugins.syslog.config

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.{SMGCmd, SMGLoggerApi, SMGObjectVar, SMGRrdObject}
import com.smule.smg.monitor.SMGMonAlertConfSource
import com.smule.smg.notify.SMGMonNotifyConf
import com.smule.smg.rrd.SMGRraDef
import com.smule.smgplugins.syslog.shared.LineData

import java.io.File
import scala.collection.mutable
import scala.util.Try

case class SMGObjectTemplate(
                              rrdBaseDir: String,
                              rrdDirIdParts: Seq[Int],
                              idPrefix: String,
                              titlePrefix: String,
                              vars: List[SMGObjectVar],
                              rrdType: String,
                              interval: Int,
                              dataDelay: Int,
                              //delay: Double,
                              stack: Boolean,
                              //preFetch: Option[String],
                              //rrdFile: Option[String],
                              rraDef: Option[SMGRraDef],
                              //rrdInitSource: Option[String],
                              notifyConf: Option[SMGMonNotifyConf],
                              labels: Map[String, String]
                            ) {

  def getPrefixedId(ld: LineData): String = idPrefix + ld.objectId
  def getRrdObjectfromLineData(prefixedId: String, ld: LineData): SMGRrdObject = {
    val rrdDir = rrdBaseDir.stripSuffix(File.separator) + File.separator +
      rrdDirIdParts.map(ix => ld.objectIdParts.lift(ix).getOrElse("INVALID")).mkString(File.separator)
    val f = new File(rrdDir)
    if (!f.exists()){
      f.mkdirs()
    }
    val rrdFile = rrdDir + File.separator + prefixedId + ".rrd"
    getRrdObjectFromIdAndFile(prefixedId, rrdFile)
  }

  def getRrdObjectFromIdAndFile(prefixedId: String, rrdFile: String ): SMGRrdObject = {
    SMGRrdObject(
      id = prefixedId,
      vars = vars,
      title = titlePrefix + prefixedId,
      parentIds = Seq(),
      command = SMGCmd(s":syslog fetch $prefixedId}"),
      rrdType = rrdType,
      interval = interval,
      dataDelay = dataDelay,
      delay = 0.0,
      stack = stack,
      preFetch = None,
      rrdFile = Some(rrdFile),
      rraDef = rraDef,
      rrdInitSource = None,
      notifyConf = notifyConf,
      labels = labels
    )
  }
}

object SMGObjectTemplate {
  def fromYmap(serverId: String, defaultInterval: Int, ymap: mutable.Map[String, Object], log: SMGLoggerApi): Option[SMGObjectTemplate] = {
    try {
      val rrdBaseDir = ymap.get("rrd_base_dir").map(_.toString)
      if (rrdBaseDir.isEmpty || rrdBaseDir.get.isEmpty){
        log.error(s"SMGObjectTemplate.fromYmap($serverId): Invalid or missing rrd_base_dir")
        return None
      }
      val varsOpt = ymap.get("vars").
        flatMap(o => Try(SMGConfigParser.yobjList(o).map(oo => SMGConfigParser.yobjMap(oo))).toOption)
      if (varsOpt.isEmpty) {
        log.error(s"SMGObjectTemplate.fromYmap($serverId): Invalid or missing vars array of maps")
        return None
      }
      val varDefs = varsOpt.get.map(m => SMGObjectVar(m.map(t => (t._1,t._2.toString)).toMap)).toList
      val interval = ymap.get("interval").map(_.toString.toInt).getOrElse(defaultInterval)
      val dataDelay = ymap.get("data_delay").map(_.toString.toInt).getOrElse(0)
      Some(SMGObjectTemplate(
        rrdBaseDir = rrdBaseDir.get,
        rrdDirIdParts = ymap.get("rrd_dir_id_parts").map(o =>
          SMGConfigParser.yobjList(o).map(_.toString.toInt)).getOrElse(Seq()),
        idPrefix = ymap.getOrElse("id_prefix", "").toString,
        titlePrefix = ymap.getOrElse("title_prefix", "").toString,
        vars = varDefs,
        rrdType = ymap.getOrElse("rrd_type", "GAUGE").toString,
        interval = interval,
        dataDelay = dataDelay,
        stack = ymap.getOrElse("stack", "false").toString == "true",
        rraDef = None, // TODO
        notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, "syslog",
           ymap.map(t => (t._1, t._2.toString)).toMap),
        labels = ymap.get("labels").map{ o =>
           SMGConfigParser.yobjMap(o).map(t => (t._1,t._2.toString)).toMap
        }.getOrElse(Map[String,String]())
      ))
    } catch { case t: Throwable =>
      log.ex(t, s"SMGObjectTemplate.fromYmap: Unexpected exception: ${t.getMessage}")
      None
    }
  }
}