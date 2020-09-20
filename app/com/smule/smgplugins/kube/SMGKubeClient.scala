package com.smule.smgplugins.kube

import com.smule.smg.core.{SMGFileUtil, SMGLoggerApi}
import com.smule.smgplugins.kube.SMGKubeClient.{KubeEndpoint, KubeEndpointPort, KubeEndpointSubset, KubeNode, KubeService, KubeServicePort}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.metrics.v1beta1.{ContainerMetrics, NodeMetrics, PodMetrics}
import io.fabric8.kubernetes.client.{Config, ConfigBuilder, DefaultKubernetesClient, KubernetesClientException}

import scala.collection.JavaConverters._

object SMGKubeClient {
  case class KubeNode(name: String, hostName: Option[String], ipAddress: Option[String])

  trait KubePort {
    val port: Int
    val protocol: String
    val name: Option[String]
    lazy val portName: String = name.getOrElse(port.toString)
  }

  trait KubeNsObject {
    val name: String
    val namespace: String
  }

  case class KubeServicePort(port: Int, protocol: String,
                             name: Option[String], nodePort: Option[Int],
                             targetPort: Option[String]) extends KubePort
  case class KubeService(name: String, namespace: String, svcType: String,
                         clusterIp: String, ports: Seq[KubeServicePort]) extends KubeNsObject

  case class KubeEndpointPort(port: Int, protocol: String, name: Option[String]) extends KubePort
  case class KubeEndpointSubset(addresses: Seq[String], ports: Seq[KubeEndpointPort])
  case class KubeEndpoint(name: String, namespace: String,
                          subsets: Seq[KubeEndpointSubset]) extends KubeNsObject
}

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

  def listNamespaces(): Seq[String] = {
    client.namespaces().list().getItems.asScala.map(_.getMetadata.getName)
  }

  def listServices(): Seq[KubeService] = {
    client.services().inAnyNamespace().list().getItems.asScala.map { svc =>
      KubeService(
      name = Option(svc.getMetadata.getName).getOrElse("UNDEFINED_NAME"),
      namespace = Option(svc.getMetadata.getNamespace).getOrElse("default"),
      svcType = Option(svc.getSpec.getType).getOrElse("ClusterIP"),
      clusterIp = Option(svc.getSpec.getClusterIP).getOrElse("UNDEFINED_CLUSTER_IP"),
      ports = Option(svc.getSpec.getPorts).map(_.asScala).getOrElse(Seq()).map { svcPort =>
        KubeServicePort(
          port = Option(svcPort.getPort).map(_.toInt)
           getOrElse(0), // TODO this is error condition
          protocol = Option(svcPort.getProtocol).getOrElse("TCP"),
          name = Option(svcPort.getName),
          nodePort = Option(svcPort.getNodePort).map(_.toInt),
          targetPort = Option(svcPort.getTargetPort).map(_.toString)
        )
      }
      )
    }
  }

  def listEndpoints(): Seq[KubeEndpoint] = {
    client.endpoints().inAnyNamespace().list().getItems.asScala.map { ep =>
      KubeEndpoint(
        name = Option(ep.getMetadata.getName).getOrElse("UNDEFINED_NAME"),
        namespace = Option(ep.getMetadata.getNamespace).getOrElse("UNDEFINED_NAME"),
        subsets = ep.getSubsets.asScala.map { eps =>
          KubeEndpointSubset(
            eps.getAddresses.asScala.map(_.getIp),
            eps.getPorts.asScala.map { epp =>
              KubeEndpointPort(
                port = epp.getPort,
                protocol = Option(epp.getProtocol).getOrElse("TCP"),
                name = Option(epp.getName)
              )
            }
          )
        }
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
