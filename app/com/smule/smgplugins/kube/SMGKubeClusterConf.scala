package com.smule.smgplugins.kube

import com.smule.smg.core.SMGFilter
import com.smule.smg.notify.SMGMonNotifyConf
import com.smule.smgplugins.scrape.RegexReplaceConf

case class SMGKubeClusterConf(
                               uid: String,
                               humanName: Option[String],
                               interval: Int,
                               fetchCommand: String,
                               fetchCommandTimeout: Int,
                               filter: Option[SMGFilter],
                               idPrefix: Option[String],
                               regexReplaces: Seq[RegexReplaceConf],
                               nodeMetrics: Seq[SMGKubeClusterMetricsConf],
                               autoConfs: Seq[SMGKubeClusterAutoConf],
                               parentPfId: Option[String],
                               parentIndexId: Option[String],
                               notifyConf: Option[SMGMonNotifyConf],
                               authConf: SMGKubeClusterAuthConf,
                               prefixIdsWithClusterId: Boolean,
                               kubectlTopStats: Boolean,
                               rraDefAgg: Option[String],
                               rraDefDtl: Option[String],
                               needParse: Boolean
                             ) {
  lazy val hnamePrefix: String = if (prefixIdsWithClusterId) humanName.getOrElse(uid) + " " else ""

  lazy val uidPrefix: String = if (prefixIdsWithClusterId) uid + "." else ""

  lazy val inspect: String = s"uid=$uid humanNamePrefix=$hnamePrefix interval=$interval command=$fetchCommand " +
    s"timeout=$fetchCommandTimeout nodeMetrics=${nodeMetrics.size} " +
    s"auto_confs=${autoConfs.map(x => x.targetType).mkString(",")}" +
    s"filter: ${filter.map(_.humanText).getOrElse("None")}"

  lazy val clusterIndexId: Option[String] = Some("cluster."+uid) // TODO maybe optional?
  lazy val nodesIndexId: Option[String] = clusterIndexId.map(_ + ".node")
  lazy val endpointsIndexId: Option[String] = clusterIndexId.map(_ + ".endpoint")
  lazy val servicesIndexId: Option[String] = clusterIndexId.map(_ + ".service")
  lazy val podPortsIndexId: Option[String] = clusterIndexId.map(_ + ".pod_port")
  lazy val kubectlTopIndexId: Option[String] = clusterIndexId.map(_ + ".kubectl.top")
}
