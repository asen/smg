package com.smule.smgplugins.kube

import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.SMGFilter
import com.smule.smg.monitor.SMGMonAlertConfSource
import com.smule.smg.notify.SMGMonNotifyConf
import com.smule.smgplugins.scrape.RegexReplaceConf

import scala.collection.mutable

case class SMGKubeNodeMetricsConf(
                                   uid: String,
                                   humanName: Option[String],
                                   interval: Option[Int],
                                   portAndPath: String,
                                   proto: Option[String],
                                   filter: Option[SMGFilter],
                                   regexReplaces: Seq[RegexReplaceConf],
                                   labelsInUids: Boolean,
//                                   parentPfId: Option[String],
//                                   parentIndexId: Option[String],
                                   notifyConf: Option[SMGMonNotifyConf],
                                   sortIndexes: Boolean
                                 ) {
  lazy val hname: String = humanName.getOrElse(uid)
}

object SMGKubeNodeMetricsConf {

  def fromYamlMap(ymap: mutable.Map[String, Object], pluginId: String): Option[SMGKubeNodeMetricsConf] = {
    if (!ymap.contains("uid")){
      return None
    }
    val uid = ymap("uid").toString
    val notifyConf = SMGMonNotifyConf.fromVarMap(
      // first two technically unused
      SMGMonAlertConfSource.OBJ,
      pluginId + "." + uid,
      ymap.toMap.map(t => (t._1, t._2.toString))
    )

    Some(
      SMGKubeNodeMetricsConf(
        uid = uid,
        humanName = ymap.get("name").map(_.toString),
        interval = ymap.get("interval").map(_.asInstanceOf[Int]),
        portAndPath = ymap.get("port_path").map(_.toString).getOrElse("/metrics"),
        proto = ymap.get("proto").map(_.toString),
        filter = if (ymap.contains("filter")){
          Some(SMGFilter.fromYamlMap(yobjMap(ymap("filter")).toMap))
        } else None,
        regexReplaces = ymap.get("regex_replaces").map { yo: Object =>
          yobjList(yo).flatMap { o =>  RegexReplaceConf.fromYamlObject(yobjMap(o)) }
        }.getOrElse(Seq()),
        labelsInUids = ymap.getOrElse("labels_in_uids", "false").toString == "true",
        notifyConf = notifyConf,
        sortIndexes = ymap.getOrElse("sort_indexes", "false").toString == "true"
      )
    )
  }
}
