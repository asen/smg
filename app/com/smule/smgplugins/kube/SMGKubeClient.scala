package com.smule.smgplugins.kube

import com.smule.smg.core.{SMGFileUtil, SMGLoggerApi}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.metrics.v1beta1.{ContainerMetrics, NodeMetrics, PodMetrics}
import io.fabric8.kubernetes.client.{Config, ConfigBuilder, DefaultKubernetesClient, KubernetesClientException}

import scala.collection.JavaConverters._

class SMGKubeClient(log: SMGLoggerApi, clusterUid: String, authConf: SMGKubeClusterAuthConf) {

  private def createClient(): DefaultKubernetesClient = {
    if (authConf.useDefault)
      new DefaultKubernetesClient()
    else if (authConf.confFile.isDefined){
      val confData = SMGFileUtil.getFileContents(authConf.confFile.get)
      val conf = Config.fromKubeconfig(confData)
      val ret = new DefaultKubernetesClient(conf)
      ret
    } else if (authConf.saTokenFile.isDefined){
      val tokenData = SMGFileUtil.getFileContents(authConf.saTokenFile.get).strip()
      var builder = new ConfigBuilder()
      if (authConf.clusterUrl.isDefined){
        builder = builder.withMasterUrl(authConf.clusterUrl.get)
      }
      builder = builder.
        withTrustCerts(true). // TODO
        withOauthToken(tokenData)
      new DefaultKubernetesClient(builder.build())
    } else {
      log.error(s"SMGKubeClient($clusterUid).createClient: unexpected authConf ($authConf) - trying default")
      new DefaultKubernetesClient()
    }
  }

  private val client = try {
    createClient()
  } catch { case t: Throwable =>
    log.ex(t, s"SMGKubeClient: Failed to create DefaultKubernetesClient for ${clusterUid}")
    throw t
  }

  def close(): Unit = {
    client.close()
  }

//  def listPods(): Seq[String] = {
////    val client = new DefaultKubernetesClient()
//    try {
////      val nsList = client.namespaces().list()
////      nsList.getItems
//      val podList = client.pods().inAnyNamespace().list()
//      podList.getItems.asScala.map(_.toString)
//    } catch { case e: KubernetesClientException =>
//      log.error(e.getMessage, e)
//      Seq()
//    } finally {
//      if (client != null) client.close()
//    }
//  }

  case class KubeNode(name: String, hostName: Option[String], ipAddress: Option[String])

  def listNodes(): Seq[KubeNode] = {
    client.nodes().list().getItems.asScala.map { n =>
      val nodeAddresses = n.getStatus.getAddresses.asScala
      val ipAddress = nodeAddresses.find(_.getType == "InternalIP").map(_.getAddress)
      val hostName = nodeAddresses.find(_.getType == "Hostname").map(_.getAddress)
      KubeNode(
        name = n.getMetadata.getName,
        hostName = hostName,
        ipAddress = ipAddress
      )
    }
  }

//  def topNodes() = {
////    val client = new DefaultKubernetesClient()
//    try {
//      val nodeMetricList = client.top.nodes.metrics
//      log.info("==== Node Metrics  ====")
//      nodeMetricList.getItems.asScala.foreach { nodeMetrics: NodeMetrics =>
//        log.info("{}\tCPU: {}{}\tMemory: {}{}",
//          nodeMetrics.getMetadata.getName,
//          nodeMetrics.getUsage.get("cpu").getAmount,
//          nodeMetrics.getUsage.get("cpu").getFormat,
//          nodeMetrics.getUsage.get("memory").getAmount,
//          nodeMetrics.getUsage.get("memory").getFormat)
//      }
//      log.info("==== Pod Metrics ====")
//      client.inAnyNamespace().top.pods.metrics().getItems.asScala.foreach{ podMetrics: PodMetrics =>
//        podMetrics.getContainers.asScala.foreach((containerMetrics: ContainerMetrics) =>
//          log.info("{}\t{}\tCPU: {}{}\tMemory: {}{}",
//            podMetrics.getMetadata.getName, containerMetrics.getName,
//            containerMetrics.getUsage.get("cpu").getAmount, containerMetrics.getUsage.get("cpu").getFormat,
//            containerMetrics.getUsage.get("memory").getAmount, containerMetrics.getUsage.get("memory").getFormat))
//      }
//
//      client.pods.inAnyNamespace().list.getItems.asScala.foreach { pod: Pod =>
//        log.info("==== Individual Pod Metrics ({}) ====", pod.getMetadata.getName)
//        val podMetrics = client.top.pods.metrics(pod.getMetadata.getNamespace, pod.getMetadata.getName)
//        podMetrics.getContainers.asScala.foreach { containerMetrics =>
//          log.info("{}\t{}\tCPU: {}{}\tMemory: {}{}",
//            podMetrics.getMetadata.getName,
//            containerMetrics.getName,
//            containerMetrics.getUsage.get("cpu").getAmount,
//            containerMetrics.getUsage.get("cpu").getFormat,
//            containerMetrics.getUsage.get("memory").getAmount,
//            containerMetrics.getUsage.get("memory").getFormat)
//        }
//      }
//    } catch { case e: KubernetesClientException =>
//      log.error(e.getMessage, e);
//    } finally {
//      if (client != null) client.close()
//    }
//  }
}
