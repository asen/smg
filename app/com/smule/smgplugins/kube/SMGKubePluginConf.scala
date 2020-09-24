package com.smule.smgplugins.kube

case class SMGKubePluginConf(
                              scrapeTargetsD: String,
                              clusterConfs: Seq[SMGKubeClusterConf]
                            ) {
   lazy val clusterConfByUid: Map[String, SMGKubeClusterConf] =
     clusterConfs.groupBy(_.uid).map { case (k, v) =>
       (k, v.head)
     }
}

object SMGKubePluginConf {
  val empty: SMGKubePluginConf =
    SMGKubePluginConf(scrapeTargetsD = "/etc/smg/scrape-targets.d", clusterConfs = Seq())
}