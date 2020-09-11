package com.smule.smgplugins.kube

import com.smule.smg.core.SMGFilter
import com.smule.smgplugins.scrape.RegexReplaceConf

case class SMGKubeClusterSvcConf(
                                  enabled: Boolean,
                                  filter: Option[SMGFilter],
                                  regexReplaces: Seq[RegexReplaceConf],
                                  reCheckBackoff: Long = 600000L
                                )

object SMGKubeClusterSvcConf {
  val disabled: SMGKubeClusterSvcConf =
    SMGKubeClusterSvcConf(enabled = false, filter = None, regexReplaces = Seq())
  val enabledDefault: SMGKubeClusterSvcConf =
    SMGKubeClusterSvcConf(enabled = false, filter = None, regexReplaces = Seq())
}
