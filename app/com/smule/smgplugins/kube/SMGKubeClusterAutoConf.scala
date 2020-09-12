package com.smule.smgplugins.kube

import com.smule.smg.core.SMGFilter
import com.smule.smgplugins.scrape.RegexReplaceConf
import com.smule.smgplugins.scrape.SMGScrapeTargetConf.{yobjList, yobjMap}

import scala.collection.mutable

case class SMGKubeClusterAutoConf(
                                   targetType: String,
                                   enabled: Boolean,
                                   filter: Option[SMGFilter],
                                   regexReplaces: Seq[RegexReplaceConf],
                                   reCheckBackoff: Long,
                                   useHttps: Boolean
                                 )

object SMGKubeClusterAutoConf {

  private val defaultRecheckBackoff: Long = 600000L

  def disabled(ttype: String): SMGKubeClusterAutoConf =
    SMGKubeClusterAutoConf(ttype, enabled = false, filter = None,
      regexReplaces = Seq(), defaultRecheckBackoff, useHttps = false)
  def enabledDefault(ttype: String): SMGKubeClusterAutoConf =
    SMGKubeClusterAutoConf(ttype, enabled = false, filter = None,
      regexReplaces = Seq(), defaultRecheckBackoff, useHttps = false)

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
        reCheckBackoff = scm.get("check_backoff").map(_.asInstanceOf[Long]).
          getOrElse(defaultRecheckBackoff),
        useHttps = scm.get("use_https").exists(_.toString == "true")
      )
    }
  }
}
