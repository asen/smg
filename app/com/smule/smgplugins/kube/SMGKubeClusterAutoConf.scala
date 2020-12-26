package com.smule.smgplugins.kube

import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.SMGFilter
import com.smule.smgplugins.scrape.RegexReplaceConf

import scala.collection.mutable

case class SMGKubeClusterAutoConf(
                                   targetType: String,
                                   enabled: Boolean,
                                   filter: Option[SMGFilter],
                                   regexReplaces: Seq[RegexReplaceConf],
                                   metricsEnableAnnotation: Option[String],
                                   metricsPortAnnotation: Option[String],
                                   metricsPathAnnotation: Option[String],
                                   reCheckBackoff: Long,
                                   tryHttps: Boolean,
                                   forceHttps: Boolean,
                                   disableCheck: Boolean
                                 )

object SMGKubeClusterAutoConf {

  private val defaultRecheckBackoff: Long = 600000L

  def disabled(ttype: String): SMGKubeClusterAutoConf =
    SMGKubeClusterAutoConf(ttype, enabled = false, filter = None,
      regexReplaces = Seq(),
      metricsEnableAnnotation = None, metricsPortAnnotation = None, metricsPathAnnotation = None,
      defaultRecheckBackoff, tryHttps = false, forceHttps = false, disableCheck = false)
  def enabledDefault(ttype: String): SMGKubeClusterAutoConf =
    SMGKubeClusterAutoConf(ttype, enabled = false, filter = None,
      regexReplaces = Seq(),
      metricsEnableAnnotation = None, metricsPortAnnotation = None, metricsPathAnnotation = None,
      defaultRecheckBackoff, tryHttps = false, forceHttps = false, disableCheck = false)

  def fromYamlMap(ymap: mutable.Map[String, Object], confKey: String): SMGKubeClusterAutoConf = {
    if (!ymap.contains(confKey))
      SMGKubeClusterAutoConf.disabled(confKey)
    else if (ymap(confKey) == null)
      SMGKubeClusterAutoConf.enabledDefault(confKey)
    else {
      val scm = yobjMap(ymap(confKey))
      SMGKubeClusterAutoConf(
        targetType = confKey,
        enabled = scm.get("enabled").forall(_.toString != "false"),
        filter = scm.get("filter").map(yo => SMGFilter.fromYamlMap(yobjMap(yo).toMap)),
        regexReplaces = scm.get("regex_replaces").map{ yo =>
          yobjList(yo).flatMap { ym =>
            RegexReplaceConf.fromYamlObject(yobjMap(ym))
          }
        }.getOrElse(Seq()),
        metricsEnableAnnotation = scm.get("metrics_enable_annotation").map(_.toString),
        metricsPortAnnotation = scm.get("metrics_port_annotation").map(_.toString),
        metricsPathAnnotation = scm.get("metrics_path_annotation").map(_.toString),
        reCheckBackoff = scm.get("check_backoff").map(_.asInstanceOf[Long]).
          getOrElse(defaultRecheckBackoff),
        tryHttps = scm.get("try_https").map(_.toString).getOrElse("true") != "false",
        forceHttps = scm.get("force_https").map(_.toString).getOrElse("false") == "true",
        disableCheck = scm.getOrElse("disable_check", "false") == "true"
      )
    }
  }
}
