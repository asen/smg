package com.smule.smgplugins.kube

case class SMGKubePluginConf(
                              scrapeTargetsD: String,
                              clusterConfs: Seq[SMGKubeClusterConf]
                            ) {

}

object SMGKubePluginConf {
  val empty: SMGKubePluginConf = SMGKubePluginConf(scrapeTargetsD = "/etc/smg/scrape-targets.d", clusterConfs = Seq())
}