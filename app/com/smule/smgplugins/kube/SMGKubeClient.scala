package com.smule.smgplugins.kube

import com.smule.smg.core.{SMGFileUtil, SMGLoggerApi}
import com.smule.smg.openmetrics.OpenMetricsStat
import com.smule.smgplugins.kube.SMGKubeClient._
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.{Config, ConfigBuilder, DefaultKubernetesClient}

import scala.collection.JavaConverters._
import scala.collection.mutable

object SMGKubeClient {
  case class KubeNode(name: String, hostName: Option[String], ipAddress: Option[String])

  trait KubePort {
    val port: Int
    val protocol: String
    val name: Option[String]
    def portName(forcePortNum: Boolean = false): String = if (forcePortNum)
      name.map(_ + "-").getOrElse("") + port.toString
    else
      name.getOrElse(port.toString)
  }

  trait KubeNsObject {
    val name: String
    val namespace: String
    val labels: Map[String, String]
    val annotations: Map[String, String]
  }

  case class KubeNamedObject(
                              name: String,
                              namespace: String,
                              labels: Map[String, String],
                              annotations: Map[String, String]
                            ) extends KubeNsObject

  case class KubeServicePort(port: Int, protocol: String,
                             name: Option[String], nodePort: Option[Int],
                             targetPort: Option[String]) extends KubePort
  case class KubeService(name: String, namespace: String, svcType: String,
                         clusterIp: String,
                         ports: Seq[KubeServicePort],
                         labels: Map[String, String],
                         annotations: Map[String, String]
                        ) extends KubeNsObject

  case class KubeEndpointPort(port: Int, protocol: String, name: Option[String]) extends KubePort
  case class KubeEndpointSubset(addresses: Seq[String], ports: Seq[KubeEndpointPort])
  case class KubeEndpoint(name: String, namespace: String,
                          subsets: Seq[KubeEndpointSubset],
                          labels: Map[String, String],
                          annotations: Map[String, String]
                         ) extends KubeNsObject

  case class KubeTopUsage(map: Map[String, Double]) {
    lazy val cpu: Double = map.getOrElse("cpu", 0.0)
    lazy val memory: Double = map.getOrElse("memory", 0.0)
  }

  object KubeTopUsage {
    def apply(in: java.util.Map[String, Quantity]): KubeTopUsage = {
      val m = in.asScala.map{ case (k, v) =>
        (k, Quantity.getAmountInBytes(v).doubleValue())
      }.toMap
      KubeTopUsage(m)
    }
  }

  case class KubeTopNamedUsage(name: String, usage: KubeTopUsage, labels: Map[String,String])
  case class KubeTopNodesResult(tsms: Long, nodesUsage: List[KubeTopNamedUsage])

  case class KubePodOwner(kind: String, name: String)  {
    private lazy val stripSuffixTokens = kind match {
      case "DaemonSet" => 1            //"-xxxxx"
      case "ReplicaSet" => 2            //"-xxxxxxxxxx-xxxxx"
      case "StatefulSet" => 0           //"-x"
      case "Job" => 1                   //"-<timestamp>-xxxxx"
      case "ReplicationController" => 1 //"-xxxxx"
      case "Node" => 0                  //drop nothing
      case _ => 0
    }

    def podStableUid(podName: String, groupIndex: Option[Int]): String = {
      var stableName = if (stripSuffixTokens > 0){
        val ret = podName.split("-").dropRight(stripSuffixTokens).mkString("-")
        if (ret.isBlank)
          podName
        else
          ret
      } else podName
      if (kind == "Job" && kind.matches("-\\d\\d\\d\\d\\d\\d\\d\\d\\d\\d$"))
        stableName = stableName.split("-").dropRight(1).mkString("-")
      OpenMetricsStat.groupIndexUid(stableName, groupIndex)
    }
  }

  case class KubePod(
                      name: String,
                      namespace: String,
                      node: String,
                      owner: Option[KubePodOwner],
                      labels: Map[String,String],
                      annotations: Map[String, String],
                      podIp: Option[String],
                      ports: Seq[KubeEndpointPort]
                    ) extends KubeNsObject {
    def stableUid(groupIndex: Option[Int]): String = namespace + "." +
      owner.map(_.podStableUid(name, groupIndex)).getOrElse(name)
  }



  case class KubeTopPodUsage(kubePod: KubePod,
                             containersUsage: List[KubeTopNamedUsage]) {
    lazy val usage: KubeTopUsage = {
      // sum of all container usages
      val mm = mutable.Map[String,Double]()
      containersUsage.foreach { cu =>
        cu.usage.map.foreach { case (k,v) =>
          if (!mm.contains(k)) mm(k) = 0.0
          mm(k) += v
        }
      }
      KubeTopUsage(mm.toMap)
    }
  }

  case class KubeTopPodsResult(tsms: Long, podsUsage: List[KubeTopPodUsage])
}

class SMGKubeClient(log: SMGLoggerApi,
                    clusterUid: String,
                    authConf: SMGKubeClusterAuthConf,
                    callTimeoutSec: Int
                   ) {

  private def createClient(): DefaultKubernetesClient = {
    val myTimeoutMs = callTimeoutSec * 1000
    val extraSec = if (callTimeoutSec < 6) 1000 else 0
    val connectTimeoutMs = (myTimeoutMs / 3) + extraSec
    val requestTimeoutMs = myTimeoutMs - connectTimeoutMs  + extraSec
    if (authConf.confFile.isDefined) {
      val confData = SMGFileUtil.getFileContents(authConf.confFile.get)
      val conf = Config.fromKubeconfig(confData)
      conf.setConnectionTimeout(connectTimeoutMs)
      conf.setRequestTimeout(requestTimeoutMs)
      new DefaultKubernetesClient(conf)
    } else if (authConf.saTokenFile.isDefined) {
      var builder = new ConfigBuilder()
      val tokenData = SMGFileUtil.getFileContents(authConf.saTokenFile.get).strip()
      if (authConf.clusterUrl.isDefined) {
        builder = builder.withMasterUrl(authConf.clusterUrl.get)
      }
      builder = builder.
        withTrustCerts(true). // TODO
        withOauthToken(tokenData).
        withConnectionTimeout(connectTimeoutMs).
        withRequestTimeout(requestTimeoutMs)
      new DefaultKubernetesClient(builder.build())
    } else {
      if (!authConf.useDefault){
        log.warn(s"SMGKubeClient($clusterUid).createClient: unexpected authConf ($authConf) - trying default")
      }
      val conf = Config.autoConfigure(null)
      conf.setConnectionTimeout(connectTimeoutMs)
      conf.setRequestTimeout(requestTimeoutMs)
      new DefaultKubernetesClient(conf)
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
        },
        labels = Option(svc.getMetadata.getLabels).map(_.asScala.toMap).getOrElse(Map()),
        annotations = Option(svc.getMetadata.getAnnotations).map(_.asScala.toMap).getOrElse(Map())
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
        },
        labels = Option(ep.getMetadata.getLabels).map(_.asScala.toMap).getOrElse(Map()),
        annotations = Option(ep.getMetadata.getAnnotations).map(_.asScala.toMap).getOrElse(Map())
      )
    }
  }

  def topNodes: KubeTopNodesResult = {
    val nodeMetricList = client.top.nodes.metrics
    val myTsms = System.currentTimeMillis()
    val nodesUsage = nodeMetricList.getItems.asScala.map { nm =>
      KubeTopNamedUsage(nm.getMetadata.getName, KubeTopUsage(nm.getUsage),
        Option(nm.getMetadata.getLabels).map(_.asScala.toMap).getOrElse(Map()))
    }
    KubeTopNodesResult(myTsms, nodesUsage.toList)
  }

  def listPods: Seq[KubePod] = {
    client.pods.inAnyNamespace().list.getItems.asScala.map { jpod =>
      val podName = jpod.getMetadata.getName
      val podNamespace = Option(jpod.getMetadata.getNamespace).getOrElse("default")
      val owners = jpod.getMetadata.getOwnerReferences.asScala.map { ow =>
        KubePodOwner(ow.getKind, ow.getName)
      }
      val owner = if (owners.isEmpty)
        None
      else {
        if (owners.tail.nonEmpty){
          log.warn(s"SMGKubeClient.getPodOwners: multiple owners returned " +
            s"for $podNamespace.$podName: ${owners.mkString(",")}")
        }
        owners.headOption
      }
      val podNodeName = Option(jpod.getSpec.getNodeName).getOrElse("undefined")
      val labels = Option(jpod.getMetadata.getLabels).map(_.asScala.toMap).getOrElse(Map()) ++
        Map("smg_pod_node" -> podNodeName)
      val ports = jpod.getSpec.getContainers.asScala.flatMap{ c => c.getPorts.asScala }.map { cp =>
        KubeEndpointPort(port = cp.getContainerPort,
          protocol = Option(cp.getProtocol).getOrElse("None"),
          name = Option(cp.getName))
      }
      KubePod(podName,
        podNamespace,
        podNodeName,
        owner,
        labels,
        annotations = Option(jpod.getMetadata.getAnnotations).map(_.asScala.toMap).getOrElse(Map()),
        Option(jpod.getStatus.getPodIP),
        ports
      )
    }
  }

  private def namespaceName(name: String, namespace: Option[String]): String =
    s"${namespace.getOrElse("default")}.${name}"

  private def namespaceName(obj: KubeNsObject): String =
    namespaceName(obj.name, Some(obj.namespace))

  private def getAllPodsByNamespaceName: Map[String, KubePod] = {
    listPods.groupBy(kp => namespaceName(kp)).map { case (k,v) =>
      if (v.lengthCompare(1) > 0)
        log.error(s"SMGKubeClient.getAllPodsByNamespaceName: Duplicate pod names: ${k} : $v")
      (k,v.head)
    }
  }

  def topPods: KubeTopPodsResult = {
    val podsMap = getAllPodsByNamespaceName
    val podMetrics = client.inAnyNamespace().top.pods.metrics()
    val myTsms = System.currentTimeMillis()
    val podUsages = podMetrics.getItems.asScala.flatMap { pm =>
      val podNamespaceName = namespaceName(pm.getMetadata.getName, Option(pm.getMetadata.getNamespace))
      val podOpt = podsMap.get(podNamespaceName)
      if (podOpt.isEmpty) {
        log.error(s"SMGKubeClient.topPods: Could not find pod info " +
          s"for $podNamespaceName  (podsMap.size=${podsMap.size})")
        None
      } else {
        val contUsages = pm.getContainers.asScala.map { cm =>
          KubeTopNamedUsage(cm.getName, KubeTopUsage(cm.getUsage), Map())
        }
        Some(KubeTopPodUsage(podOpt.get, contUsages.toList))
      }
    }
    KubeTopPodsResult(myTsms, podUsages.toList)
  }
}
