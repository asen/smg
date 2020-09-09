package com.smule.smgplugins.kube

import scala.collection.mutable

case class SMGKubeClusterAuthConf(
                                   useDefault: Boolean,
                                   confFile: Option[String],
                                   saTokenFile: Option[String],
                                   clusterUrl: Option[String]
                                 ) {

}

object SMGKubeClusterAuthConf {

  val default: SMGKubeClusterAuthConf =
    SMGKubeClusterAuthConf(useDefault = true, confFile = None,
      saTokenFile = None, clusterUrl = None)

  def fromConfFile(confFile: String): SMGKubeClusterAuthConf =
    SMGKubeClusterAuthConf(useDefault = false, confFile = Some(confFile),
      saTokenFile = None, clusterUrl = None)

  def fromTokenFileAndUrl(tokenFile: String, clusterEndpoint: Option[String]): SMGKubeClusterAuthConf =
    SMGKubeClusterAuthConf(useDefault = false, confFile = None,
      saTokenFile = Some(tokenFile), clusterUrl = clusterEndpoint)

  def fromYamlObject(yobj: mutable.Map[String, Object]): SMGKubeClusterAuthConf = {
    if (yobj.contains("kube_conf_file"))
      fromConfFile(yobj("kube_conf_file").toString)
    else if (yobj.contains("kube_token_file")){
      val clusterUrl = yobj.get("kube_url").map(_.toString)
      fromTokenFileAndUrl(yobj("kube_token_file").toString, clusterUrl)
    } else
      default
  }

}