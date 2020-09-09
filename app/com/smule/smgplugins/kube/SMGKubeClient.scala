package com.smule.smgplugins.kube

import com.smule.smg.core.SMGLoggerApi
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.metrics.v1beta1.{ContainerMetrics, NodeMetrics, PodMetrics}
import io.fabric8.kubernetes.client.{DefaultKubernetesClient, KubernetesClientException}

import scala.collection.JavaConverters._

class SMGKubeClient(log: SMGLoggerApi) {

  val client = new DefaultKubernetesClient()

  def listPods(): Seq[String] = {
//    val client = new DefaultKubernetesClient()
    try {
//      val nsList = client.namespaces().list()
//      nsList.getItems
      val podList = client.pods().inAnyNamespace().list()
      podList.getItems.asScala.map(_.toString)
    } catch { case e: KubernetesClientException =>
      log.error(e.getMessage, e)
      Seq()
    } finally {
      if (client != null) client.close()
    }
  }

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

  def topNodes() = {
//    val client = new DefaultKubernetesClient()
    try {
      val nodeMetricList = client.top.nodes.metrics
      log.info("==== Node Metrics  ====")
      nodeMetricList.getItems.asScala.foreach { nodeMetrics: NodeMetrics =>
        log.info("{}\tCPU: {}{}\tMemory: {}{}",
          nodeMetrics.getMetadata.getName,
          nodeMetrics.getUsage.get("cpu").getAmount,
          nodeMetrics.getUsage.get("cpu").getFormat,
          nodeMetrics.getUsage.get("memory").getAmount,
          nodeMetrics.getUsage.get("memory").getFormat)
      }
      log.info("==== Pod Metrics ====")
      client.inAnyNamespace().top.pods.metrics().getItems.asScala.foreach{ podMetrics: PodMetrics =>
        podMetrics.getContainers.asScala.foreach((containerMetrics: ContainerMetrics) =>
          log.info("{}\t{}\tCPU: {}{}\tMemory: {}{}",
            podMetrics.getMetadata.getName, containerMetrics.getName,
            containerMetrics.getUsage.get("cpu").getAmount, containerMetrics.getUsage.get("cpu").getFormat,
            containerMetrics.getUsage.get("memory").getAmount, containerMetrics.getUsage.get("memory").getFormat))
      }

      client.pods.inAnyNamespace().list.getItems.asScala.foreach { pod: Pod =>
        log.info("==== Individual Pod Metrics ({}) ====", pod.getMetadata.getName)
        val podMetrics = client.top.pods.metrics(pod.getMetadata.getNamespace, pod.getMetadata.getName)
        podMetrics.getContainers.asScala.foreach { containerMetrics =>
          log.info("{}\t{}\tCPU: {}{}\tMemory: {}{}",
            podMetrics.getMetadata.getName,
            containerMetrics.getName,
            containerMetrics.getUsage.get("cpu").getAmount,
            containerMetrics.getUsage.get("cpu").getFormat,
            containerMetrics.getUsage.get("memory").getAmount,
            containerMetrics.getUsage.get("memory").getFormat)
        }
      }
    } catch { case e: KubernetesClientException =>
      log.error(e.getMessage, e);
    } finally {
      if (client != null) client.close()
    }
  }
}
