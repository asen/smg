package com.smule.smgplugins.kube

import com.smule.smg.core.SMGFilter
import com.smule.smg.monitor.SMGMonNotifyConf
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
                               svcConf: SMGKubeClusterSvcConf,
                               parentPfId: Option[String],
                               parentIndexId: Option[String],
                               notifyConf: Option[SMGMonNotifyConf],
                               authConf: SMGKubeClusterAuthConf
                             ) {
  lazy val hname: String = humanName.getOrElse(uid)

  lazy val inspect: String = s"uid=$uid humanName=$hname interval=$interval command=$fetchCommand " +
    s"timeout=$fetchCommandTimeout nodeMetrics=${nodeMetrics.size} " +
    s"filter: ${filter.map(_.humanText).getOrElse("None")}"
}
