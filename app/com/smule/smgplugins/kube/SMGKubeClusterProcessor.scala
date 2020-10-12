package com.smule.smgplugins.kube

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.Date

import com.smule.smg.config.{SMGConfIndex, SMGConfigParser, SMGConfigService}
import com.smule.smg.core._
import com.smule.smg.openmetrics.OpenMetricsStat
import com.smule.smgplugins.kube.SMGKubeClient.{KubeEndpoint, KubeNamedObject, KubeNsObject, KubePod, KubePort, KubeService}
import com.smule.smgplugins.scrape.{OpenMetricsResultData, SMGScrapeTargetConf}
import org.yaml.snakeyaml.Yaml

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.util.Random

class SMGKubeClusterProcessor(pluginConfParser: SMGKubePluginConfParser,
                              smgConfSvc: SMGConfigService,
                              log: SMGLoggerApi) {
  private def pluginConf = pluginConfParser.conf // function - do not cache pluginConf

  private def processClusterNodeMetricsConf(objectName: String,
                                            targetHost: String,
                                            cConf: SMGKubeClusterConf,
                                            cmConf: SMGKubeClusterMetricsConf,
                                            parentIndexId: Option[String]): Option[SMGScrapeTargetConf] = {
    val targetType = "node"
    val tcUid = s"${cConf.uidPrefix}$targetType.$objectName.${cmConf.uid}"
    if (!SMGConfigParser.validateOid(tcUid)) {
      log.error(s"SMGKubeClusterProcessor - invalid SMG uid: $tcUid, ignoring metric conf")
      return None
    }
    val confOutput = s"${cConf.uid}-$targetType-$objectName-${cmConf.uid}.yml"
    val humanName = s"${cConf.hnamePrefix}Node $objectName ${cmConf.hname}"
    val ret = SMGScrapeTargetConf(
      uid = tcUid,
      humanName = humanName,
      command = cConf.fetchCommand + " " + cmConf.proto.getOrElse("http") +
        "://" + targetHost + cmConf.portAndPath,
      timeoutSec =  cConf.fetchCommandTimeout,
      confOutput = confOutput,
      confOutputBackupExt = None, // TODO
      filter = if (cmConf.filter.isDefined) cmConf.filter else cConf.filter,
      interval = cmConf.interval.getOrElse(cConf.interval),
      parentPfId = cConf.parentPfId,
      parentIndexId = parentIndexId,
      idPrefix = cConf.idPrefix,
      notifyConf = if (cmConf.notifyConf.isDefined) cmConf.notifyConf else cConf.notifyConf,
      regexReplaces = cConf.regexReplaces ++ cmConf.regexReplaces,
      labelsInUids = cmConf.labelsInUids,
      extraLabels = Map("smg_target_host"-> targetHost, "smg_target_port_path" -> cmConf.portAndPath),
      rraDefAgg = cConf.rraDefAgg,
      rraDefDtl = cConf.rraDefDtl,
      needParse = cConf.needParse
    )
    Some(ret)
  }

  // command -> (nsobj, last executed (good or bad), desc for bad)
  private val knownGoodServiceCommands = TrieMap[String,(KubeNsObject, Long)]()
  private val knownBadServiceCommands = TrieMap[String,(KubeNsObject, Long, String)]()

  case class AutoDiscoveredCommandStatus(nsObj: KubeNsObject, tsms: Long,
                                         command: String, reason: Option[String]){
    def tsStr: String = new Date(tsms).toString
    private def reasonOrOk = reason.map("ERROR: " + _).getOrElse("OK")
    def inspect: String = s"${nsObj.namespace}/${nsObj.name}: $command (ts=$tsStr) status=$reasonOrOk"
  }

  def listAutoDiscoveredCommands: Seq[AutoDiscoveredCommandStatus] = {
    val good = knownGoodServiceCommands.toSeq.sortBy { x =>
      (x._2._1.namespace, x._2._1.name)
    }.map { x =>
      AutoDiscoveredCommandStatus(nsObj = x._2._1, tsms = x._2._2, command = x._1, reason = None)
    }
    val bad = knownBadServiceCommands.toSeq.sortBy { x =>
      (x._2._1.namespace, x._2._1.name)
    }.map { x =>
      AutoDiscoveredCommandStatus(nsObj = x._2._1, tsms = x._2._2, command = x._1, reason = Some(x._2._3))
    }
    good ++ bad
  }

  private def checkAutoConfCommand(command: String,
                            cConf: SMGKubeClusterConf,
                            autoConf: SMGKubeClusterAutoConf,
                            kubeNsObject: KubeNsObject,
                            kubePort: KubePort): Boolean = {
    // filter out invalid ports as far as we can tell
    def logSkipped(reason: String): Unit = {
      log.info(s"SMGKubeClusterProcessor.checkAutoConf(${autoConf.targetType}): ${cConf.uid} " +
        s"${kubeNsObject.namespace}.${kubeNsObject.name}: skipped due to $reason")
    }
    val lastGoodRunTs = knownGoodServiceCommands.get(command).map(_._2)
    if (lastGoodRunTs.isDefined) {
      // re-check occasionally?
      // TODO: right now - once good, its good until SMG restart
      // note that removed services will not even get here and will disappear automatically
      return true
    }
    val lastBadRunTs = knownBadServiceCommands.get(command).map(_._2)
    if (lastBadRunTs.isDefined){
      val randomizedBackoff = autoConf.reCheckBackoff +
        Random.nextInt(autoConf.reCheckBackoff.toInt).toLong
      if (System.currentTimeMillis() - lastBadRunTs.get < randomizedBackoff) {
        //logSkipped(s"known bad command: $command")
        return false
      }
    }

    // actual checks below
    if (kubePort.protocol != "TCP") {
      val reason = s"protocol=${kubePort.protocol} (not TCP)"
      logSkipped(reason)
      knownBadServiceCommands.put(command, (kubeNsObject, System.currentTimeMillis(), reason))
      return false
    }
    try {
      val outObj = smgConfSvc.runFetchCommand(SMGCmd(command, cConf.fetchCommandTimeout), None)
      if (cConf.needParse) {
        val out = outObj.asStr
        if (OpenMetricsStat.parseText(out, log, labelsInUid = false).nonEmpty) {
          //keep known up services in a cache and not run this every minute -
          //we only want to know if it is http and has valid /metrics URL, once
          knownGoodServiceCommands.put(command, (kubeNsObject, System.currentTimeMillis()))
          knownBadServiceCommands.remove(command)
          true
        } else {
          val reason = s"command output unparse-able ($command): ${out}"
          logSkipped(reason)
          knownBadServiceCommands.put(command, (kubeNsObject, System.currentTimeMillis(), reason))
          false
        }
      } else {
        if (outObj.data.asInstanceOf[OpenMetricsResultData].stats.nonEmpty){
          knownGoodServiceCommands.put(command,  (kubeNsObject, System.currentTimeMillis()))
          knownBadServiceCommands.remove(command)
          true
        } else {
          val reason = s"command output parsed but empty ($command)"
          logSkipped(reason)
          knownBadServiceCommands.put(command,  (kubeNsObject, System.currentTimeMillis(), reason))
          false
        }
      }
    } catch { case t: Throwable => //SMGCmdException =>
      val reason = s"command or metrics parse failed: ${t.getMessage}"
      logSkipped(reason)
      knownBadServiceCommands.put(command, (kubeNsObject, System.currentTimeMillis(), reason))
      false
    }
  }

  private def checkAutoConf(commands: Seq[String],
                            cConf: SMGKubeClusterConf,
                            autoConf: SMGKubeClusterAutoConf,
                            kubeNsObject: KubeNsObject,
                            kubePort: KubePort): Option[Int] = {
    val ret = commands.indexWhere { cmd =>
      checkAutoConfCommand(cmd, cConf, autoConf, kubeNsObject, kubePort)
    }
    if (ret < 0)
      None
    else
      Some(ret)
  }

  def processAutoPortConf(cConf: SMGKubeClusterConf,
                          autoConf: SMGKubeClusterAutoConf,
                          nsObject: KubeNsObject,
                          ipAddr: String,
                          kubePort: KubePort,
                          idxId: Option[Int],
                          parentIndexId: Option[String]
                         ): Option[SMGScrapeTargetConf] = {
    try {
      def myCommand(proto: String)  = cConf.fetchCommand + " " + proto + "://" + ipAddr + s":${kubePort.port}/metrics"
      val commands = Seq(myCommand("http")) ++
        (if (autoConf.tryHttps) Seq(myCommand("https")) else Seq())
      val workingCommandIdx = checkAutoConf(commands, cConf, autoConf, nsObject, kubePort)
      if (workingCommandIdx.isEmpty)
        return None
      val command = commands(workingCommandIdx.get)
      val uid = cConf.uidPrefix + autoConf.targetType + "." + nsObject.namespace +
        "." + nsObject.name + "." + kubePort.portName + idxId.map(x => s"._$x").getOrElse("")
      val title = s"${cConf.hnamePrefix}${autoConf.targetType} " +
        s"${nsObject.namespace}.${nsObject.name}:${kubePort.portName}${idxId.map(x => s" ($x)").getOrElse("")}"
      val confOutput = s"${cConf.uid}-${autoConf.targetType}-${nsObject.namespace}-" +
        s"${nsObject.name}-${kubePort.port}${idxId.map(x => s"-$x").getOrElse("")}.yml"
      val ret = SMGScrapeTargetConf(
        uid = uid,
        humanName = title,
        command = command,
        timeoutSec = cConf.fetchCommandTimeout,
        confOutput = confOutput,
        confOutputBackupExt = None,
        filter = if (autoConf.filter.isDefined) autoConf.filter else cConf.filter,
        interval = cConf.interval,
        parentPfId = cConf.parentPfId,
        parentIndexId = parentIndexId,
        idPrefix = cConf.idPrefix,
        notifyConf = cConf.notifyConf,
        regexReplaces = cConf.regexReplaces ++ autoConf.regexReplaces,
        labelsInUids = false,
        extraLabels = Map("smg_target_type"-> autoConf.targetType,
          "smg_target_host"-> ipAddr,
          "smg_target_port" -> kubePort.port.toString) ++ nsObject.labels,
        rraDefAgg = cConf.rraDefAgg,
        rraDefDtl = cConf.rraDefDtl,
        needParse = cConf.needParse
      )
      Some(ret)
    } catch { case t: Throwable =>
      log.ex(t, s"SMGKubeClusterProcessor.processServicePortConf(${nsObject.name},${kubePort.port}): " +
        s"Unexpected error: ${t.getMessage}")
      None
    }
  }

  def processServiceConf(cConf: SMGKubeClusterConf, kubeService: KubeService): Seq[SMGScrapeTargetConf] = {
    // TODO check eligibility based on labels?
    kubeService.ports.flatMap { svcPort =>
      processAutoPortConf(cConf, cConf.svcConf, kubeService, kubeService.clusterIp, svcPort,
        None, cConf.servicesIndexId)
    }
  }

  def processEndpointConf(cConf: SMGKubeClusterConf, kubeEndpoint: KubeEndpoint): Seq[SMGScrapeTargetConf] = {
    // TODO check eligibility based on labels?
    var idx: Option[Int] = None
    if (kubeEndpoint.subsets.size > 1)
      idx = Some(0)
    kubeEndpoint.subsets.flatMap { subs =>
      if ((subs.addresses.size > 1) && idx.isEmpty)
        idx = Some(0)
      subs.addresses.flatMap { addr =>
        if (idx.isDefined) idx = Some(idx.get + 1)
        subs.ports.flatMap { prt =>
           processAutoPortConf(cConf, cConf.endpointsConf, kubeEndpoint, addr, prt, idx, cConf.endpointsIndexId)
        }
      }
    }
  }

  def processPodsPortConfs(cConf: SMGKubeClusterConf, pods: Seq[KubePod]) : Seq[SMGScrapeTargetConf] = {
    pods.groupBy { p =>
      p.owner.map { ow =>
        (ow.kind, ow.name, p.stableUid(None))
      }
    }.toSeq.sortBy(_._1).flatMap { case (gbKeyOpt, podSeq) =>
      var idx: Option[Int] = if (podSeq.size > 1)  Some(0) else None
      podSeq.flatMap { pod =>
        if (pod.podIp.isEmpty){
          log.debug(s"SMGKubeClusterProcessor.processPodPortConf(${cConf.uid}): processPodPortConf ${pod.name} has no IP")
          Seq()
        } else {
          if (idx.isDefined) idx = Some(idx.get + 1)
          val nobj = KubeNamedObject(pod.stableUid(idx), pod.namespace, pod.labels)
          pod.ports.flatMap { podPort =>
            processAutoPortConf(cConf, cConf.podPortsConf, nobj,
              pod.podIp.get, podPort, None, cConf.podPortsIndexId)
          }
        }
      }
    }
  }

  private def processKubectlTopStats(cConf: SMGKubeClusterConf): Seq[SMGScrapeTargetConf] = {
    if (!cConf.kubectlTopStats) {
      log.debug("SMGKubeClusterProcessor.processKubectlTopStats: kubectlTopStats is disabled in conf")
      return Seq()
    }
    val ret = ListBuffer[SMGScrapeTargetConf]()
    val scrapeBaseCmd = s":kube ${cConf.uid}"
    val uidPx = cConf.uidPrefix + "kubectl.top."
//    val titlePx = s"${cConf.hnamePrefix}kubectl top stats - "
    val confOutputPx = s"${cConf.uid}-kubectl-top-stats-"
    ret += SMGScrapeTargetConf(
      uid = uidPx + "nodes",
      humanName = "Kubectl Top Nodes",
      command = s"$scrapeBaseCmd top-nodes",
      timeoutSec = cConf.fetchCommandTimeout,
      confOutput = confOutputPx + "10-nodes.yml",
      confOutputBackupExt = None,
      filter = cConf.filter,
      interval = cConf.interval,
      parentPfId = cConf.parentPfId,
      parentIndexId = cConf.kubectlTopIndexId,
      idPrefix = cConf.idPrefix,
      notifyConf = cConf.notifyConf,
      regexReplaces = cConf.regexReplaces,
      labelsInUids = false,
      extraLabels = Map("smg_target_type"-> "kubectl-top-nodes"),
      rraDefAgg = cConf.rraDefAgg,
      rraDefDtl = cConf.rraDefDtl,
      needParse = cConf.needParse
    )
    val topPodsPfId = cConf.uidPrefix + KUBECTL_TOP_PODS_PF_NAME

    ret += SMGScrapeTargetConf(
      uid = uidPx + "pods",
      humanName = "Kubectl Top Pods",
      command = s"$scrapeBaseCmd top-pods",
      timeoutSec = cConf.fetchCommandTimeout,
      confOutput = confOutputPx + "20-pods.yml",
      confOutputBackupExt = None,
      filter = cConf.filter,
      interval = cConf.interval,
      parentPfId = Some(topPodsPfId),
      parentIndexId = cConf.kubectlTopIndexId,
      idPrefix = cConf.idPrefix,
      notifyConf = cConf.notifyConf,
      regexReplaces = cConf.regexReplaces,
      labelsInUids = false,
      extraLabels = Map("smg_target_type"-> "kubectl-top-pods"),
      rraDefAgg = cConf.rraDefAgg,
      rraDefDtl = cConf.rraDefDtl,
      needParse = cConf.needParse
    )
    ret += SMGScrapeTargetConf(
      uid = uidPx + "conts",
      humanName = "Kubectl Top Containers",
      command = s"$scrapeBaseCmd top-conts",
      timeoutSec = cConf.fetchCommandTimeout,
      confOutput = confOutputPx + "30-conts.yml",
      confOutputBackupExt = None,
      filter = cConf.filter,
      interval = cConf.interval,
      parentPfId = Some(topPodsPfId),
      parentIndexId = cConf.kubectlTopIndexId,
      idPrefix = cConf.idPrefix,
      notifyConf = cConf.notifyConf,
      regexReplaces = cConf.regexReplaces,
      labelsInUids = false,
      extraLabels = Map("smg_target_type"-> "kubectl-top-conts"),
      rraDefAgg = cConf.rraDefAgg,
      rraDefDtl = cConf.rraDefDtl,
      needParse = cConf.needParse
    )
    ret.toList
  }

  def getYamlText(cConf: SMGKubeClusterConf): String = {
    val kubeClient = new SMGKubeClient(log, cConf.uid, cConf.authConf, cConf.fetchCommandTimeout)
    try {
      // generate SMGScrapeTargetConf from topNodes and topPods
      val topStats = processKubectlTopStats(cConf)

      // generate metrics confs
      val nodeMetricsConfs: Seq[SMGScrapeTargetConf] = cConf.nodeMetrics.flatMap { cmConf =>
        kubeClient.listNodes().flatMap { kubeNode =>
          val targetHost = kubeNode.ipAddress.getOrElse(kubeNode.hostName.getOrElse(kubeNode.name))
          processClusterNodeMetricsConf(kubeNode.name, targetHost, cConf, cmConf, cConf.nodesIndexId)
        }
      }
      val serviceMetricsConfs: Seq[SMGScrapeTargetConf] = if (cConf.svcConf.enabled){
        kubeClient.listServices().flatMap { ksvc =>
          processServiceConf(cConf, ksvc)
        }
      } else Seq()
      val endpointsMetricsConfs: Seq[SMGScrapeTargetConf] = if (cConf.endpointsConf.enabled){
        kubeClient.listEndpoints().flatMap { kendp =>
          processEndpointConf(cConf, kendp)
        }
      } else Seq()
      val podPortsMetricsConfs: Seq[SMGScrapeTargetConf] = if (cConf.podPortsConf.enabled){
        processPodsPortConfs(cConf, kubeClient.listPods)
      } else Seq()
      // dump
      val objsLst = new java.util.ArrayList[Object]()
      // TODO need to insert indexes ?
      (topStats ++ nodeMetricsConfs ++ serviceMetricsConfs ++
        endpointsMetricsConfs ++ podPortsMetricsConfs).foreach { stConf =>
        objsLst.add(SMGScrapeTargetConf.dumpYamlObj(stConf))
      }
      val out = new StringBuilder()
      out.append(s"# This file is automatically generated. Changes will be overwritten\n")
      out.append(s"# Generated by SMGKubePlugin from cluster config ${cConf.uid}.\n")
      val yaml = new Yaml()
      out.append(yaml.dump(objsLst))
      out.append("\n")
      out.toString()
    } finally {
      kubeClient.close()
    }
  }

  def processClusterConf(cConf: SMGKubeClusterConf): Boolean = {
    // output a cluster yaml and return true if changed
    try {
      val yamlText = getYamlText(cConf)
      val confOutputFile = pluginConf.scrapeTargetsD.stripSuffix(File.separator) + (File.separator) +
        cConf.uid + ".yml"
      val oldYamlText = if (Files.exists(Paths.get(confOutputFile)))
        SMGFileUtil.getFileContents(confOutputFile)
      else
        ""
      if (oldYamlText == yamlText) {
        log.debug(s"SMGKubeClusterProcessor.processClusterConf(${cConf.uid}) - no config changes detected")
        return false
      }
      SMGFileUtil.outputStringToFile(confOutputFile, yamlText, None) // cConf.confOutputBackupExt???
      true
    } catch { case t: Throwable =>
      log.ex(t, s"SMGKubeClusterProcessor.processClusterConf(${cConf.uid}): unexpected error: ${t.getMessage}")
      false
    }
  }

  def run(): Boolean = {
    // TODO
    var ret = false
    pluginConf.clusterConfs.foreach { cConf =>
       if (processClusterConf(cConf))
         ret = true
    }
    ret
  }

  private val KUBECTL_TOP_PODS_PF_NAME = "kubectl-top-pods-pf"

  def preFetches: Seq[SMGPreFetchCmd] = pluginConfParser.conf.clusterConfs.flatMap { cConf =>
    if (cConf.kubectlTopStats){
      Seq(
        SMGPreFetchCmd(
          id = cConf.uidPrefix + KUBECTL_TOP_PODS_PF_NAME,
          command = SMGCmd(s":kube ${cConf.uid} top-pods-pf", timeoutSec = cConf.fetchCommandTimeout),
          desc = Some("Call kubectl top pods API"),
          preFetch = cConf.parentPfId,
          ignoreTs = false,
          childConc = 2,
          notifyConf = cConf.notifyConf,
          passData = true
        )
      )
    } else {
      Seq()
    }
  }

  private def myIndexDef(id: String,
                         title: String,
                         filterPrefix: String,
                         parentIndexId: Option[String]
                        ) = SMGConfIndex(
    id = id,
    title = title,
    flt = SMGFilter.fromPrefixLocal(filterPrefix),
    cols = None,
    rows = None,
    aggOp = None,
    xRemoteAgg = false,
    aggGroupBy = None,
    gbParam = None,
    period = None,
    desc = None,
    parentId = parentIndexId,
    childIds = Seq(),
    disableHeatmap = false
  )

  def indexes: Seq[SMGConfIndex] = pluginConfParser.conf.clusterConfs.flatMap { cConf =>
    // optional top-level index
    if (cConf.clusterIndexId.isDefined) {
      val idxPrefix = if (cConf.prefixIdsWithClusterId) cConf.uid + "." else ""
      val ret = ListBuffer[SMGConfIndex]()
      var myParentIndexId = cConf.parentIndexId
      if (cConf.prefixIdsWithClusterId) {
        ret += myIndexDef(cConf.clusterIndexId.get,
          s"Kubernetes cluster ${cConf.uid}",
          idxPrefix,
          cConf.parentIndexId
        )
        myParentIndexId = cConf.clusterIndexId
      }
      if (cConf.kubectlTopStats) {
        ret += myIndexDef(cConf.kubectlTopIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Kubectl Top Stats",
          idxPrefix + "kubectl.top.",
          myParentIndexId
        )
      }
      if (cConf.nodeMetrics.nonEmpty) // top level node metrics index
        ret += myIndexDef(cConf.nodesIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Nodes",
          idxPrefix + "node.",
          myParentIndexId
        )
      if (cConf.svcConf.enabled)  // top level svcs metrics index
        ret += myIndexDef(cConf.servicesIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Services",
          idxPrefix + "service.",
          myParentIndexId
        )
      if (cConf.endpointsConf.enabled) // top level endpoints metrics index
        ret += myIndexDef(cConf.endpointsIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Endpoints",
          idxPrefix + "endpoint.",
          myParentIndexId
        )
      if (cConf.podPortsConf.enabled) // top level podPorts metrics index
        ret += myIndexDef(cConf.podPortsIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - auto discovered metrics from pod listen ports",
          idxPrefix + "pod_port.",
          myParentIndexId
        )
      ret.toList
    } else Seq()
  }
}
