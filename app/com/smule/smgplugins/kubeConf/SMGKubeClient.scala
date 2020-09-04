package com.smule.smgplugins.kubeConf

import io.fabric8.kubernetes.api.model.metrics.v1beta1.{ContainerMetrics, NodeMetrics, NodeMetricsList, PodMetrics}
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import java.io.IOException

import com.smule.smg.core.SMGLoggerApi
import com.smule.smg.plugin.SMGPluginLogger
import io.fabric8.kubernetes.api.model.Pod

import scala.collection.JavaConversions._

class SMGKubeClient(log: SMGLoggerApi) {

  def listPods(): Seq[String] = {
    Seq()
  }

  def topNodes() = {
    val client = new DefaultKubernetesClient()
    try {
      val nodeMetricList = client.top.nodes.metrics
      log.info("==== Node Metrics  ====")
      nodeMetricList.getItems.foreach { nodeMetrics: NodeMetrics =>
        log.info("{}\tCPU: {}{}\tMemory: {}{}",
          nodeMetrics.getMetadata.getName,
          nodeMetrics.getUsage.get("cpu").getAmount,
          nodeMetrics.getUsage.get("cpu").getFormat,
          nodeMetrics.getUsage.get("memory").getAmount,
          nodeMetrics.getUsage.get("memory").getFormat)
      }
      log.info("==== Pod Metrics ====")
      client.inAnyNamespace().top.pods.metrics().getItems.foreach{ podMetrics: PodMetrics =>
        podMetrics.getContainers.foreach((containerMetrics: ContainerMetrics) =>
          log.info("{}\t{}\tCPU: {}{}\tMemory: {}{}",
            podMetrics.getMetadata.getName, containerMetrics.getName,
            containerMetrics.getUsage.get("cpu").getAmount, containerMetrics.getUsage.get("cpu").getFormat,
            containerMetrics.getUsage.get("memory").getAmount, containerMetrics.getUsage.get("memory").getFormat))
      }

      client.pods.inAnyNamespace().list.getItems.foreach { pod: Pod =>
        log.info("==== Individual Pod Metrics ({}) ====", pod.getMetadata.getName)
        val podMetrics = client.top.pods.metrics(pod.getMetadata.getNamespace, pod.getMetadata.getName)
        podMetrics.getContainers.foreach { containerMetrics =>
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
