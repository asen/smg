package com.smule.smgplugins.kube

import com.smule.smg.core.{CommandResult, CommandResultCustom, ParentCommandData, SMGCmdException, SMGFetchException, SMGLoggerApi}
import com.smule.smg.openmetrics.OpenMetricsStat

import scala.collection.mutable.ListBuffer

object SMGKubeCommands {
  val VALID_COMMANDS = Set("top-nodes", "top-pods-pf", "top-pods", "top-conts")
}

class SMGKubeCommands(log: SMGLoggerApi, clusterUid: String, authConf: SMGKubeClusterAuthConf) {

  private case class PodMetrics(pods: Seq[OpenMetricsStat], conts: Seq[OpenMetricsStat])

  private def podsUsagesToMetrics(tsms: Long, in: Seq[SMGKubeClient.KubeTopPodUsage]): PodMetrics = {
    val podsRet = ListBuffer[OpenMetricsStat]()
    val contsRet = ListBuffer[OpenMetricsStat]()
    val baseLabels = Seq[(String,String)](
      ("smg_cluster_id", clusterUid)
    )
    in.groupBy(_.kubePod.owner).toSeq.sortBy{ t =>
      t._2.head.kubePod.namespace + "/" + t._1.map(x => x.name).getOrElse("")
    }.foreach { case (owner, pods) =>
      val ownerLabels = baseLabels ++ Seq[(String,String)](
        ("smg_pod_owner_kind", owner.map(_.kind).getOrElse("none")),
        ("smg_pod_owner_name", owner.map(_.name).getOrElse("none"))
      )
      var groupIndex = if (pods.lengthCompare(1) > 0) Some(0) else None
      pods.sortBy(_.kubePod.name).foreach { podStats =>
        if (groupIndex.isDefined) groupIndex = Some(groupIndex.get + 1)
        val podLabels = ownerLabels ++ Seq[(String,String)](
          ("smg_top_pod", podStats.kubePod.name)
        )
        val podKey = s"pod.${podStats.kubePod.stableUid(groupIndex)}"
        val podCpuKey = s"${podKey}.cpu"
        val podMemKey = s"${podKey}.mem"
        podsRet += OpenMetricsStat(
          smgUid = podCpuKey,
          metaKey = Some(podCpuKey),
          metaType = Some("gauge"),
          metaHelp = Some(s"pod ${podStats.kubePod.name} cpu used"),
          name = podCpuKey,
          labels = podLabels ++ Seq(
            ("smg_top_stat", "cpu")
          ),
          value = podStats.usage.cpu,
          tsms = Some(tsms),
          groupIndex = None
        )
        podsRet +=  OpenMetricsStat(
          smgUid = podMemKey,
          metaKey = Some(podMemKey),
          metaType = Some("gauge"),
          metaHelp = Some(s"pod ${podStats.kubePod.name} memory used"),
          name = podMemKey,
          labels = podLabels ++ Seq(
            ("smg_top_stat", "memory")
          ),
          value = podStats.usage.memory,
          tsms = Some(tsms),
          groupIndex = None
        )
        val contPodKey = s"cont.${podStats.kubePod.stableUid(groupIndex)}"
        podStats.containersUsage.sortBy(_.name).foreach { contStats =>
          val contKey = s"${contPodKey}.${contStats.name}"
          val contCpuKey = s"${contKey}.cpu"
          val contMemKey = s"${contKey}.mem"
          contsRet += OpenMetricsStat(
            smgUid = contCpuKey,
            metaKey = Some(contCpuKey),
            metaType = Some("gauge"),
            metaHelp = Some(s"kubectl top pod ${podStats.kubePod.namespace}.${podStats.kubePod.name}, " +
              s"container ${contStats.name} cpu used"),
            name = contCpuKey,
            labels = podLabels ++ Seq(
              ("smg_top_stat", "cpu"),
              ("smg_top_container", contStats.name)
            ),
            value = contStats.usage.cpu,
            tsms = Some(tsms),
            groupIndex = None
          )
          contsRet +=  OpenMetricsStat(
            smgUid = contMemKey,
            metaKey = Some(contMemKey),
            metaType = Some("gauge"),
            metaHelp = Some(s"pod ${podStats.kubePod.name}, " +
              s"container ${contStats.name} memory used"),
            name = contMemKey,
            labels = podLabels ++ Seq(
              ("smg_top_stat", "memory"),
              ("smg_top_container", contStats.name)
            ),
            value = contStats.usage.memory,
            tsms = Some(tsms),
            groupIndex = None
          )
        }
      }
    }
    PodMetrics(podsRet.toList, contsRet.toList)
  }

  private def nodesUsagesToMetrics(tsms: Long, in: Seq[SMGKubeClient.KubeTopNamedUsage]): Seq[OpenMetricsStat] = {
    in.sortBy(_.name).flatMap { nu =>
      val nodeKey = s"${nu.name}"
      val nodeCpuKey = s"${nodeKey}.cpu"
      val nodeMemKey = s"${nodeKey}.mem"
      Seq(
        OpenMetricsStat(
          smgUid = nodeCpuKey,
          metaKey = Some(nodeCpuKey),
          metaType = Some("gauge"),
          metaHelp = Some(s"kubectl top node ${nu.name} cpu"),
          name = nodeCpuKey,
          labels = Seq(
            ("smg_cluster_id", clusterUid),
            ("smg_top_node", nu.name),
            ("smg_top_stat", "cpu")
          ),
          value = nu.usage.cpu,
          tsms = Some(tsms),
          groupIndex = None
        ),
        OpenMetricsStat(
          smgUid = nodeMemKey,
          metaKey = Some(nodeMemKey),
          metaType = Some("gauge"),
          metaHelp = Some(s"kubectl top node ${nu.name} memory used"),
          name = nodeMemKey,
          labels = Seq(
            ("smg_cluster_id", clusterUid),
            ("smg_top_node", nu.name),
            ("smg_top_stat", "memory")
          ),
          value = nu.usage.memory,
          tsms = Some(tsms),
          groupIndex = None
        )
      )
    }
  }

  private def commandTopNodes(timeoutSec: Int,
                              parentData: Option[ParentCommandData]): CommandResult = {
    val cli = new SMGKubeClient(log, clusterUid, authConf, timeoutSec)
    try {
      val topNodesResult = try {
        cli.topNodes
      } catch { case t: Throwable =>
        val errMsg = s"SMGKubeCommands: top-nodes: unexpected error: ${t.getMessage}"
        log.ex(t, errMsg)
        throw new SMGFetchException(errMsg)
      }
      val openMetrics = nodesUsagesToMetrics(System.currentTimeMillis(), topNodesResult.nodesUsage)
      CommandResultCustom(OpenMetricsStat.dumpStats(openMetrics).mkString("\n") + "\n")
    } finally {
      cli.close()
    }
  }

  private def topPodsCommon(timeoutSec: Int): PodMetrics ={
    val cli = new SMGKubeClient(log, clusterUid, authConf, timeoutSec)
    try {
      try {
        val topPodsResult = cli.topPods
        podsUsagesToMetrics(System.currentTimeMillis(), topPodsResult.podsUsage)
      } catch { case t: Throwable =>
        val errMsg = s"SMGKubeCommands: top-pods: unexpected error: ${t.getMessage}"
        log.ex(t, errMsg)
        throw new SMGFetchException(errMsg)
      }
    } finally {
      cli.close()
    }
  }

  private def commandTopPods(timeoutSec: Int,
                             parentData: Option[ParentCommandData]): CommandResult = {
    val openMetrics = if (parentData.isDefined)
      parentData.get.res.data.asInstanceOf[PodMetrics]
    else {
      topPodsCommon(timeoutSec)
    }
    CommandResultCustom(OpenMetricsStat.dumpStats(openMetrics.pods).mkString("\n") + "\n")
  }

  private def commandTopPodsConts(timeoutSec: Int,
                              parentData: Option[ParentCommandData]): CommandResult = {
    val openMetrics = if (parentData.isDefined)
      parentData.get.res.data.asInstanceOf[PodMetrics]
    else {
      topPodsCommon(timeoutSec)
    }
    CommandResultCustom(OpenMetricsStat.dumpStats(openMetrics.conts).mkString("\n") + "\n")
  }

  def runPluginFetchCommand(cmd: String,
                            timeoutSec: Int,
                            parentData: Option[ParentCommandData]): CommandResult = {
    val arr = cmd.split("\\s+")
    val action = arr(0)
    if (!SMGKubeCommands.VALID_COMMANDS.contains(action)){
      throw new SMGCmdException(cmd, timeoutSec, -1, "", s"Invalid command action: ${action}")
    }
    action match {
      case "top-nodes" => commandTopNodes(timeoutSec, parentData)
      case "top-pods-pf" => CommandResultCustom(topPodsCommon(timeoutSec))
      case "top-pods" =>  commandTopPods(timeoutSec, parentData)
      case "top-conts" => commandTopPodsConts(timeoutSec, parentData)
    }
  }
}
