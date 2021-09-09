package com.smule.smgplugins.kube

import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.{SMGFilter, SMGLoggerApi}
import com.smule.smgplugins.kube.SMGKubeClusterAutoConf.ConfType
import com.smule.smgplugins.scrape.RegexReplaceConf

import scala.collection.mutable
import scala.util.Try

case class SMGKubeClusterAutoConf(
                                   targetType: ConfType.Value,
//                                   aconfUid: Option[String],
                                   enabled: Boolean,
                                   filter: Option[SMGFilter],
                                   regexReplaces: Seq[RegexReplaceConf],

                                   metricsEnableAnnotation: Option[String],
//                                   metricsPortAnnotation: Option[String],
//                                   metricsPathAnnotation: Option[String],

                                   autoconfAnnotationsPrefix: String,
                                   prometheusAnnotationsPrefix: String,
                                   discoverMetrics: Boolean,
                                   discoverProto: Option[String],
                                   discoverMetricsPath: String,
                                   discoverTemplate: Option[String],
                                   discoverBackoffSeconds: Int,
                                   discoverBackoffShuffle: Int,
                                   discoverSuccessBackoffMultiplier: Int
//                                   ,reCheckBackoff: Long,
//                                   tryHttps: Boolean,
//                                   forceHttps: Boolean,
//                                   disableCheck: Boolean,
//                                   labelsInUids: Boolean,
//                                   sortIndexes: Boolean
                                 )

object SMGKubeClusterAutoConf {

  object ConfType extends Enumeration {
    val endpoint, service, pod_port = Value
  }

  val defaultRecheckBackoff: Long = 600000L

  def fromYamlMap(scm: mutable.Map[String, Object], log: SMGLoggerApi): Option[SMGKubeClusterAutoConf] = {
    val confType: Option[ConfType.Value] = Try(ConfType.withName(scm("type").toString)).toOption
    if (confType.isEmpty){
      log.warn(s"SMGKubeClusterAutoConf: invalid auto_conf type: $scm")
      return None
    }
    val ret = SMGKubeClusterAutoConf(
      targetType = confType.get,
//      aconfUid = scm.get("uid").map(_.toString),
      enabled = scm.get("enabled").forall(_.toString != "false"),
      filter = scm.get("filter").map(yo => SMGFilter.fromYamlMap(yobjMap(yo).toMap)),
      regexReplaces = scm.get("regex_replaces").map{ yo =>
        yobjList(yo).flatMap { ym =>
          RegexReplaceConf.fromYamlObject(yobjMap(ym))
        }
      }.getOrElse(Seq()),
      metricsEnableAnnotation = scm.get("metrics_enable_annotation").map(_.toString),
//      metricsPortAnnotation = scm.get("metrics_port_annotation").map(_.toString),
//      metricsPathAnnotation = scm.get("metrics_path_annotation").map(_.toString),

      autoconfAnnotationsPrefix = scm.getOrElse("ac_annotations_prefix", "smg.autoconf").toString,
      prometheusAnnotationsPrefix = scm.getOrElse("prom_annotations_prefix", "prometheus.io/").toString,
      discoverMetrics = scm.getOrElse("discover", "false").toString == "true",
      discoverProto = scm.get("discover_proto").map(_.toString),
      discoverMetricsPath = scm.getOrElse("discover_metrics_path", "/metrics").toString,
      discoverTemplate = scm.get("discover_template").map(_.toString),
      discoverBackoffSeconds = scm.get("discover_backoff").map(_.asInstanceOf[Int]).getOrElse(300),
      discoverBackoffShuffle = scm.get("discover_backoff_shuffle").map(_.asInstanceOf[Int]).
        getOrElse(Integer.valueOf(600)),
      discoverSuccessBackoffMultiplier = scm.get("discover_success_backoff_multiplier").
        map(_.asInstanceOf[Int]).getOrElse(10)

      //      ,reCheckBackoff = scm.get("check_backoff").map(_.asInstanceOf[Long]).
//        getOrElse(defaultRecheckBackoff),
//      tryHttps = scm.get("try_https").map(_.toString).getOrElse("true") != "false",
//      forceHttps = scm.get("force_https").map(_.toString).getOrElse("false") == "true",
//      disableCheck = scm.getOrElse("disable_check", "false").toString == "true",
//      labelsInUids = scm.getOrElse("labels_in_uids", "false").toString == "true",
//      sortIndexes = scm.getOrElse("sort_indexes", "false").toString == "true"
    )
    Some(ret)
  }
}
