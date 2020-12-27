package com.smule.smgplugins.kube

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.Date
import com.smule.smg.config.{SMGConfIndex, SMGConfigParser, SMGConfigService}
import com.smule.smg.core._
import com.smule.smg.openmetrics.OpenMetricsStat
import com.smule.smgplugins.kube.SMGKubeClient.{KubeEndpoint, KubeNamedObject, KubeNsObject, KubePod, KubePort, KubeService}
import com.smule.smgplugins.kube.SMGKubeClusterAutoConf.ConfType
import com.smule.smgplugins.scrape.{OpenMetricsResultData, SMGScrapeTargetConf}
import org.yaml.snakeyaml.Yaml

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.util.{Random, Try}

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

  private def logSkipped(kubeNsObject: KubeNsObject, targetType: String,
                         clusterUid: String, reason: String): Unit = {
    log.info(s"SMGKubeClusterProcessor.checkAutoConf(${targetType}): ${clusterUid} " +
      s"${kubeNsObject.namespace}.${kubeNsObject.name}: skipped due to $reason")
  }

  private def checkAutoConfCommand(command: String,
                            cConf: SMGKubeClusterConf,
                            autoConf: SMGKubeClusterAutoConf,
                            kubeNsObject: KubeNsObject,
                            kubePort: KubePort): Boolean = {
    // filter out invalid ports as far as we can tell
    def logSkipped(reason: String): Unit = this.logSkipped(kubeNsObject, autoConf.targetType.toString, cConf.uid, reason)
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
                          metricsPath: Option[String],
                          idxId: Option[Int],
                          parentIndexId: Option[String],
                          forcePortNums: Boolean
                         ): Option[SMGScrapeTargetConf] = {
    try {
      val uid = cConf.uidPrefix + autoConf.targetType + "." + nsObject.namespace +
        "." + nsObject.name + "." + kubePort.portName(forcePortNums) + idxId.map(x => s"._$x").getOrElse("")
      val title = s"${cConf.hnamePrefix}${autoConf.targetType} " +
        s"${nsObject.namespace}.${nsObject.name}:${kubePort.portName(forcePortNums)}${idxId.map(x => s" ($x)").getOrElse("")}"
      if (autoConf.filter.isDefined){
        val flt = autoConf.filter.get
        if (!flt.matchesId(uid)){
          logSkipped(nsObject, autoConf.targetType.toString, cConf.uid, "skipped due filter not matching id")
          return None
        }
      }
      val myMetricsPath = metricsPath.getOrElse("/metrics")
      def myCommand(proto: String)  = cConf.fetchCommand + " " + proto + "://" + ipAddr + s":${kubePort.port}${myMetricsPath}"
      val commands = (if (!autoConf.forceHttps) Seq(myCommand("http")) else Seq()) ++
        (if (autoConf.tryHttps || autoConf.forceHttps) Seq(myCommand("https")) else Seq())
      val workingCommandIdx = if (autoConf.disableCheck) {
        Some(0)
      } else
        checkAutoConf(commands, cConf, autoConf, nsObject, kubePort)
      if (workingCommandIdx.isEmpty)
        return None
      val command = commands(workingCommandIdx.get)
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
        extraLabels = Map("smg_target_type"-> autoConf.targetType.toString,
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

  def processMetricsAnnotations(ports: Seq[KubePort],
                                nobj: KubeNsObject,
                                cConf: SMGKubeClusterConf,
                                autoConf: SMGKubeClusterAutoConf): (Seq[KubePort], Option[String]) = {
    val myPath = autoConf.metricsPathAnnotation.flatMap(lbl => nobj.annotations.get(lbl))
    if (autoConf.metricsEnableAnnotation.isDefined) {
      if (nobj.annotations.getOrElse(autoConf.metricsEnableAnnotation.get, "false") == "true"){
        val myPorts = if (autoConf.metricsPortAnnotation.isDefined){
          val opt: Option[Int] = Try(nobj.annotations.get(autoConf.metricsPortAnnotation.get).
            map(_.toInt)).toOption.flatten
          ports.filter(p => opt.contains(p.port))
        } else ports
        (myPorts, myPath)
      } else {
        log.info(s"SMGKubeClusterProcessor.processMetricsLabels(${cConf.uid}): " +
          s"${nobj.getClass.getName} ${nobj.name} skipped due to " +
          s"metricsEnableAnnotation=${autoConf.metricsEnableAnnotation.get}")
        (Seq(), myPath)
      }
    } else { //no annotation
      (ports, myPath)
    }
  }

  def processServiceConf(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                         kubeService: KubeService): Seq[SMGScrapeTargetConf] = {
    val hasDupPortNames = kubeService.ports.map(_.portName(false)).distinct.size != kubeService.ports.size
    val nobj = kubeService
    val (ports, path) = processMetricsAnnotations(kubeService.ports, nobj, cConf, autoConf)
    ports.flatMap { prt =>
      processAutoPortConf(cConf, autoConf, nobj,
        kubeService.clusterIp, prt, metricsPath = path, idxId = None,
        parentIndexId = cConf.endpointsIndexId, forcePortNums = hasDupPortNames)
    }
  }

  def processEndpointConf(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                          kubeEndpoint: KubeEndpoint): Seq[SMGScrapeTargetConf] = {
    var idx: Option[Int] = None
    if (kubeEndpoint.subsets.size > 1)
      idx = Some(0)
    kubeEndpoint.subsets.flatMap { subs =>
      if ((subs.addresses.size > 1) && idx.isEmpty)
        idx = Some(0)
      subs.addresses.flatMap { addr =>
        if (idx.isDefined) idx = Some(idx.get + 1)
        val hasDupPortNames = subs.ports.map(_.portName(false)).distinct.size != subs.ports.size
        val nobj = kubeEndpoint
        val (ports, path) = processMetricsAnnotations(subs.ports, nobj, cConf, autoConf)
        ports.flatMap { prt =>
          processAutoPortConf(cConf, autoConf, nobj,
            addr, prt, metricsPath = path, idxId = idx,
            parentIndexId = cConf.endpointsIndexId, forcePortNums = hasDupPortNames)
        }
      }
    }
  }

  def processPodsPortConfs(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                           pods: Seq[KubePod]) : Seq[SMGScrapeTargetConf] = {
    pods.groupBy { p =>
      p.owner.map { ow =>
        (ow.kind, ow.name, p.stableUid(None))
      }
    }.toSeq.sortBy(_._1).flatMap { case (gbKeyOpt, podSeq) =>
      var idx: Option[Int] = if (podSeq.size > 1)  Some(0) else None
      podSeq.flatMap { pod =>
        if (pod.podIp.isEmpty){
          log.warn(s"SMGKubeClusterProcessor.processPodPortConf(${cConf.uid}): " +
            s"processPodPortConf ${pod.name} has no IP")
          Seq()
        } else {
          if (idx.isDefined) idx = Some(idx.get + 1)
          val nobj = KubeNamedObject(pod.stableUid(idx), pod.namespace, pod.labels, pod.annotations)
          // check for dup port names
          val hasDupPortNames =
            pod.ports.map(_.portName(false)).distinct.lengthCompare(pod.ports.size) != 0
          val (ports, path) = processMetricsAnnotations(pod.ports, pod, cConf, autoConf)
          ports.flatMap { podPort =>
            processAutoPortConf(cConf, autoConf, nobj,
              pod.podIp.get, podPort, metricsPath = path, idxId = None,
              parentIndexId = cConf.podPortsIndexId, forcePortNums = hasDupPortNames)
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
      val autoMetricsConfs: Seq[SMGScrapeTargetConf] = cConf.autoConfs.filter(_.enabled).flatMap { aConf =>
        aConf.targetType match {
          case ConfType.service =>
            kubeClient.listServices().flatMap { ksvc =>
              processServiceConf(cConf, aConf, ksvc)
            }
          case ConfType.endpoint =>
            kubeClient.listEndpoints().flatMap { kendp =>
              processEndpointConf(cConf, aConf, kendp)
            }
          case ConfType.pod_port =>
            processPodsPortConfs(cConf, aConf, kubeClient.listPods)
        }
      }
      // dump
      val objsLst = new java.util.ArrayList[Object]()
      // TODO need to insert indexes ?
      (topStats ++ nodeMetricsConfs ++ autoMetricsConfs).foreach { stConf =>
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
          passData = true,
          delay = 0.0
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
      if (cConf.autoConfs.exists(x => x.targetType == ConfType.service && x.enabled))  // top level svcs metrics index
        ret += myIndexDef(cConf.servicesIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Services",
          idxPrefix + "service.",
          myParentIndexId
        )
      if (cConf.autoConfs.exists(x => x.targetType == ConfType.endpoint && x.enabled)) // top level endpoints metrics index
        ret += myIndexDef(cConf.endpointsIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Endpoints",
          idxPrefix + "endpoint.",
          myParentIndexId
        )
      if (cConf.autoConfs.exists(x => x.targetType == ConfType.pod_port && x.enabled)) // top level podPorts metrics index
        ret += myIndexDef(cConf.podPortsIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - auto discovered metrics from pod listen ports",
          idxPrefix + "pod_port.",
          myParentIndexId
        )
      ret.toList
    } else Seq()
  }
}
