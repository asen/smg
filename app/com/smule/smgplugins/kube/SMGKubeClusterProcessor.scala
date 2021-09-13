package com.smule.smgplugins.kube

import com.smule.smg.config.{SMGConfIndex, SMGConfigParser, SMGConfigService}
import com.smule.smg.core._
import com.smule.smg.openmetrics.OpenMetricsParser
import com.smule.smgplugins.autoconf.SMGAutoTargetConf
import com.smule.smgplugins.kube.SMGKubeClient._
import com.smule.smgplugins.kube.SMGKubeClusterAutoConf.ConfType
import com.smule.smgplugins.scrape.OpenMetricsResultData
import org.yaml.snakeyaml.Yaml

import java.io.File
import java.nio.file.{Files, Paths}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Random, Try}

class SMGKubeClusterProcessor(pluginConfParser: SMGKubePluginConfParser,
                              smgConfSvc: SMGConfigService,
                              log: SMGLoggerApi) {
  private def pluginConf = pluginConfParser.conf // function - do not cache pluginConf

  case class KubeAutoconfTargetSummary(atConf: SMGAutoTargetConf) {
    def inspect: String = {
      s"output=${atConf.confOutput} template=${atConf.template} " +
        s"command=${atConf.command.getOrElse("None")} context=${atConf.context.size}"
    }
  }

  private val autoconfTargetSummaries = TrieMap[SMGKubeClusterConf, List[KubeAutoconfTargetSummary]]()

  def getAutoconfTargetSummaries(cConf: SMGKubeClusterConf): Option[List[KubeAutoconfTargetSummary]] = {
    autoconfTargetSummaries.get(cConf)
  }

  // (clusterId,command) -> (success, expires, nsObjectName)
  private val autoDiscoveryCommandsCache = TrieMap[(String,String),(Boolean,Long,String)]()

  def getAutoDiscoveryCommandsStatus: Seq[String] = {
    val tssNow = System.currentTimeMillis()
    autoDiscoveryCommandsCache.toSeq.map { t =>
      val (clusterId, command) = t._1
      val (success, expires, nsObjectName) = t._2
      s"$clusterId.${nsObjectName}: $command (success=$success, expires in ${(expires - tssNow) / 1000} s)"
    }
  }

  private def logSkipped(namespace: String, name: String, targetType: String,
                         clusterUid: String, reason: String): Unit = {
    val msg = s"SMGKubeClusterProcessor.checkAutoConf(${targetType}): ${clusterUid} " +
      s"${namespace}.${name}: skipped due to $reason"
    if (pluginConf.logSkipped)
      log.info(msg)
    else
      log.debug(msg)
  }

//  private def logSkipped(kubeNsObject: KubeNsObject, targetType: String,
//                         clusterUid: String, reason: String): Unit = {
//    logSkipped(kubeNsObject.namespace, kubeNsObject.name, targetType, clusterUid, reason)
//  }

  private val INTEGER_REGEX = "^-?\\d+$".r
  private val DOUBLE_REGEX = "^[^\\+][\\d\\.\\+-E]+$".r

  private def objectifyString(s: String): Object = {
    if (INTEGER_REGEX.findFirstIn(s).isDefined)
      return Try(Integer.valueOf(s.toInt)).getOrElse(s)
    if (DOUBLE_REGEX.findFirstIn(s).isDefined)
      return Try(Double.box(s.toDouble)).getOrElse(s)
    if (s == "true")
      return Boolean.box(true)
    if (s == "false")
      return Boolean.box(false)
    s
  }

  private val SEQ_TYPE_PREFIX = "_seq-"
  private val MAP_TYPE_PREFIX = "_map-"
  // smg.autoconf-0/template: blah
  // smg.autoconf-0/fs_label_filters._list-a: "device!=tmpfs"
  // smg.autoconf-0/fs_label_filters._list-b: "mountpoint!=/boot"
  // smg.autoconf-0/net_dvc_filters._map-my_key: "my value"
  // smg.autoconf-0/net_dvc_filters._map-my_other_key: "my other value"
  private def getAutoconfAnnotationGroup(tseq: Seq[(String, String)]): Map[String,Object] = {
    val retObjects = mutable.Map[String, Object]()
    val retSeqObjects = mutable.Map[String, mutable.Map[String, Object]]()
    val retMapObjects = mutable.Map[String, mutable.Map[String, Object]]()
    tseq.map { case (k,v) =>
      val myK = k.split("/",2).lift(1).getOrElse("")
      (myK, v)
    }.foreach { case (myk,v) =>
      val arr = myk.split("\\.", 2)
      val mytyp = arr.lift(1).getOrElse("")
      if (mytyp.startsWith(SEQ_TYPE_PREFIX)){
        val seqName = arr(0)
        val seqIndex = mytyp.stripPrefix(SEQ_TYPE_PREFIX)
        val seqBuf = retSeqObjects.getOrElseUpdate(seqName, mutable.Map())
        seqBuf.put(seqIndex, objectifyString(v))
      } else if (mytyp.startsWith(MAP_TYPE_PREFIX)){
        val mapName = arr(0)
        val mapKey = mytyp.stripPrefix(MAP_TYPE_PREFIX)
        val mapBuf = retMapObjects.getOrElseUpdate(mapName, mutable.Map())
        mapBuf.put(mapKey, objectifyString(v))
      } else {
        retObjects.put(myk, objectifyString(v))
      }
    }
    retObjects.toMap ++ retSeqObjects.map { case (k,v) =>
      // sort the "index" -> "value" map by index and get the values
      (k, v.toSeq.sortBy(_._1).map(_._2).toList)
    }.toMap ++ retMapObjects.map { case (k,v) =>
      (k,v.toMap)
    }.toMap
  }

  private def getAutoconfAnnotationGropus(autoConf: SMGKubeClusterAutoConf, annotations: Map[String,String]): Seq[Map[String,Object]] = {
    val myAnnotationGroups = annotations.filter{ t =>
      t._1.startsWith(autoConf.autoconfAnnotationsPrefix) || t._1.startsWith(autoConf.prometheusAnnotationsPrefix)
    }.toSeq.groupBy { t =>
      t._1.split("/",2)(0)
    }
    myAnnotationGroups.toSeq.map { case (_, tseq) =>
      getAutoconfAnnotationGroup(tseq)
    }
  }

  private def expandAutoconfAnnotationPorts(ports: Seq[KubePort],
                                    annotationsMap: Map[String,Object]): Seq[Map[String,Object]] = {
    val staticPorts = annotationsMap.get("ports").
      map(_.toString).map(_.split("\\s*,\\s*").toSeq).getOrElse(
        annotationsMap.get("port").map(_.toString).map(x => Seq(x)).getOrElse(Seq())
      )
    val actualPorts = if (staticPorts.nonEmpty) {
      staticPorts.map { port_str =>
        val kubePort = ports.find(p => p.port.toString == port_str || p.name.contains(port_str))
        if (kubePort.isDefined) {
          (kubePort.get.port.toString, kubePort.get.name.getOrElse(kubePort.get.port.toString))
        } else {
          (port_str, port_str)
        }
      }
    } else {
      ports.map(kp => (kp.port.toString, kp.name.getOrElse(kp.port.toString)))
    }
    val hasDupPortNames = actualPorts.map(_._2).distinct.size != actualPorts.size
    actualPorts.map { port =>
      annotationsMap ++ Map(
        "port" -> Try(Integer.valueOf(port._1)).getOrElse(Integer.valueOf(0)),
        "port_name" -> (if (hasDupPortNames) port._1 else port._2),
        "kube_source" -> "annotations"
      )
    }
  }

  private def runDiscoveryMetricsCommand(cConf: SMGKubeClusterConf, cmd: String): Boolean = {
    try {
      val cr = smgConfSvc.runFetchCommand(SMGCmd(cmd, cConf.defaultFetchCommandTimeout), None)
      val parsed = if (cConf.needParse) {
        OpenMetricsParser.parseText(cr.asStr, Some(log))
      } else {
        cr.data.asInstanceOf[OpenMetricsResultData].stats
      }
      parsed.exists(_.rows.nonEmpty)
    } catch { case t: Throwable =>
      false
    }
  }

  private def checkDiscoveryMetricsCommand(cConf: SMGKubeClusterConf,
                                           aConf: SMGKubeClusterAutoConf,
                                           nobj: KubeNsObject,
                                           cmd: String): Boolean = {
    val ret = autoDiscoveryCommandsCache.getOrElseUpdate( (cConf.uid, cmd), {
      val success = runDiscoveryMetricsCommand(cConf, cmd)
      var backoff = aConf.discoverBackoffSeconds + Random.nextInt(aConf.discoverBackoffShuffle)
      if (success) backoff *= aConf.discoverSuccessBackoffMultiplier
      val expires = System.currentTimeMillis() + (backoff * 1000L)
      (success, expires, s"${aConf.targetType}.${nobj.namespace}.${nobj.name}")
    })
    ret._1
  }

  private def discoverMetrics(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                              nobj: KubeNsObject, ipAddr: String, ports: Seq[KubePort]): Seq[Map[String, Object]] = {
    if (ipAddr == "") {
      logSkipped(nobj.namespace, nobj.name, autoConf.targetType.toString, cConf.uid,
        s"empty ip address")
      return Seq()
    }

    def portFetchCmd(proto: String, portNum: Int): String =
      s"${cConf.defaultFetchCommand} " +
        s"${proto}://${ipAddr}:${portNum}/${autoConf.discoverMetricsPath.stripPrefix("/")}"

    val protos = if (autoConf.discoverProto.isDefined)
      Seq(autoConf.discoverProto.get)
    else
      Seq("http", "https")

    val ret = ports.filter(_.protocol.toLowerCase != "udp").flatMap { kPort =>
      val workingProtoOpt: Option[String] = protos.find { proto =>
        val cmdToTry = portFetchCmd(proto, kPort.port)
        checkDiscoveryMetricsCommand(cConf, autoConf, nobj, cmdToTry)
      }
      workingProtoOpt.map { proto =>
        Map[String,Object](
          "proto" -> proto,
          "port" -> Integer.valueOf(kPort.port),
          "path" -> autoConf.discoverMetricsPath,
          "kube_source" -> "discovered"
        )
      }
    }
    if (ret.isEmpty) {
      logSkipped(nobj.namespace, nobj.name, autoConf.targetType.toString, cConf.uid,
        s"no metrics auto discovered via " +
          s"proto(s)=${protos.mkString(",")} port(s)=${ports.map(_.port).mkString(",")} " +
          s"path=${autoConf.discoverMetricsPath}")
    }
    ret
  }

  private def processAutoconfAnnotations(
                                          cConf: SMGKubeClusterConf,
                                          autoConf: SMGKubeClusterAutoConf,
                                          nobj: KubeNsObject,
                                          ipAddr: String,
                                          ports: Seq[KubePort],
                                          nobjAnnotations: Map[String,String]
                                        ): Seq[Map[String, Object]] = {
    if (autoConf.metricsEnableAnnotation.isDefined) {
      // explicitly disabled
      if (nobjAnnotations.getOrElse(autoConf.metricsEnableAnnotation.get, "") == "false") {
        logSkipped(nobj.namespace, nobj.name, autoConf.targetType.toString, cConf.uid,
          s"disabled via annotation: ${autoConf.metricsEnableAnnotation.get}=false")
        return Seq()
      }
    }
    // apply filter first
    if (autoConf.filter.isDefined) {
      val oid = nobj.namespace + "." + nobj.name
      val matchesFilter = autoConf.filter.get.matchesId(oid) &&
        autoConf.filter.get.matchesLabelsMap(nobj.labels)
      if (!matchesFilter) {
        logSkipped(nobj.namespace, nobj.name, autoConf.targetType.toString, cConf.uid,
          s"filter not matching id or labels: $oid")
        return Seq()
      }
    }
    val myAutoconfGroups = getAutoconfAnnotationGropus(autoConf, nobjAnnotations)
    val ret = if (myAutoconfGroups.isEmpty && autoConf.discoverMetrics) {
      // auto discovery mode
      discoverMetrics(cConf, autoConf, nobj, ipAddr, ports)
    } else {
      // annotations based mode
      myAutoconfGroups.flatMap { annotationsMap =>
        expandAutoconfAnnotationPorts(ports, annotationsMap)
      }
    }
    ret.map(_ ++ autoConf.staticContext)
  }

  private def processAutoconfMap(
                                  autoconfMap: Map[String, Object],
                                  cConf: SMGKubeClusterConf,
                                  targetTypeVal: ConfType.Value,
                                  filter: Option[SMGFilter],
                                  kubeObjectName: String,
                                  kubeObjectNamespace: Option[String],
                                  kubeObjectLabels: Map[String,String],
                                  ipAddr: Option[String],
                                  idxId: Option[Int],
                                  parentIndexId: Option[String]
                                ): Option[SMGAutoTargetConf] = {

    val targetType = targetTypeVal.toString
    val namespaceStr = kubeObjectNamespace.map(_ + ".").getOrElse("")
    val contextMap = mutable.Map[String,Object]()
    contextMap ++= autoconfMap
    try {
      val portOpt = contextMap.get("port")
      val template = if (contextMap.contains("template"))
        contextMap("template").toString
      else {
        if (!contextMap.contains("command")){
          // generate a default scrape command
          val portStr = portOpt.map {p => s":${p}"}.getOrElse("")
          val proto = if (contextMap.contains("proto"))
            contextMap("proto").toString
          else if (contextMap.contains("scheme"))
            contextMap("scheme").toString
          else if (portStr.endsWith("443"))
            "https"
          else
            "http"
          val pathStr = contextMap.getOrElse("path", "metrics").toString.stripPrefix("/")
          val hostStr = if (ipAddr.isDefined) "%node_host%" else "%node_name%"
          val cmd = cConf.defaultFetchCommand + s""" "${proto}://${hostStr}${portStr}/${pathStr}""""
          contextMap.put("command", cmd)
          if (!contextMap.contains("runtime_data")) {
            contextMap.put("runtime_data", Boolean.box(true))
          }
        }
        cConf.defaultTemplate
      }
      if (!contextMap.contains("timeout")){
        contextMap.put("timeout", Integer.valueOf(cConf.defaultFetchCommandTimeout))
      }
      if (!contextMap.contains("interval")){
        contextMap.put("interval", Integer.valueOf(cConf.interval))
      }
      if (!contextMap.contains("rra_def") && cConf.rraDef.isDefined){
        contextMap.put("rra_def", cConf.rraDef.get)
      }
      if (!contextMap.contains("parent_index") && cConf.indexesByType){
        contextMap.put("parent_index", s"cluster.${cConf.uid}.${targetType}")
      }

      val staticUid = contextMap.remove("uid").map(_.toString)
      val templateAlias = staticUid.getOrElse(contextMap.get("template_alias").map(_.toString).getOrElse(template))
      val staticOutput = contextMap.remove("output").map(_.toString)
      val staticNodeName = contextMap.remove("node_name").map(_.toString)
      val staticResolveName = contextMap.get("resolve_name").map(_.toString)
      val staticRegenDelay = contextMap.get("regen_delay").flatMap(x => Try(x.toString.toInt).toOption)

      val commandOpt = contextMap.remove("command").map(_.toString)
      val runtimeDataOpt = contextMap.get("runtime_data").map(_.toString)
      val runtimeDataTimeoutSecOpt = contextMap.get("runtime_data_timeout_sec").flatMap(s => Try(s.toString.toInt).toOption)
      val portNameOpt = contextMap.get("port_name").map(_.toString)
      val portDotName = portNameOpt.map("." + _).getOrElse("")
      val portColName = portNameOpt.map(": " + _).getOrElse("")

      if (ipAddr.isDefined) {
        contextMap.put("node_host", ipAddr.get)
        contextMap.put("kube_host", ipAddr.get)
      }
      contextMap.put("id_prefix", cConf.uidPrefix)
      contextMap.put("kube_prefix", cConf.uidPrefix)
      contextMap.put("kube_cluster_id", cConf.uid)
      val idName =  targetType + "." + namespaceStr +
        kubeObjectName + portDotName + idxId.map(x => s"._$x").getOrElse("") +
        s".${templateAlias}"
      contextMap.put("kube_id_name", idName)
      val nodeName = staticNodeName.getOrElse(idName)
      val uid = cConf.uidPrefix + idName
      contextMap.put("kube_uid", uid)
      val title = s"${cConf.hnamePrefix}${targetType} " +
        s"${namespaceStr}${kubeObjectName}${portColName}${idxId.map(x => s" ($x)").getOrElse("")} " +
        s"- ${templateAlias}"
      contextMap.put("kube_title", title)
      contextMap.put("title_prefix", s"${cConf.hnamePrefix}${targetType} ")
      if (filter.isDefined){
        val flt = filter.get
        if (!flt.matchesId(uid)){
          logSkipped(namespaceStr, kubeObjectName, targetType.toString, cConf.uid,
            s"filter not matching id: $uid")
          return None
        }
        if (!flt.matchesLabelsMap(kubeObjectLabels)){
          logSkipped(namespaceStr, kubeObjectName, targetType.toString, cConf.uid,
            s"filter not matching labels: $uid: ${kubeObjectLabels}")
          return None
        }
      }

      val portOutputPart = portOpt.map("-" + _.toString).getOrElse(portNameOpt.map("-" + _).getOrElse(""))
      val confOutput = staticOutput.getOrElse(s"${cConf.uid}-${uid}" +
        s"${portOutputPart}${idxId.map(x => s"-$x").getOrElse("")}-${templateAlias}.yml")

      val portLabelMap = portOpt.map(n => Map("smg_target_port" -> n)).getOrElse(Map()) ++
          portNameOpt.map(n => Map("smg_target_port_name" -> n)).getOrElse(Map())
      val kubeSourceMap = if (contextMap.contains("kube_source"))
        Map("smg_kube_source" -> contextMap("kube_source"))
      else
        Map()
      val extraLabels = Map("smg_target_type"-> targetType,
        "smg_target_host"-> ipAddr.getOrElse("")) ++ kubeSourceMap ++ portLabelMap ++ kubeObjectLabels
      contextMap.put("extra_labels", extraLabels)
      val ret = SMGAutoTargetConf (
        template = template,
        output = Some(confOutput),
        uid = staticUid,
        runtimeData = runtimeDataOpt.contains("true"),
        runtimeDataTimeoutSec = runtimeDataTimeoutSecOpt,
        resolveName = staticResolveName.contains("true"),
        regenDelay = staticRegenDelay,
        nodeName = Some(nodeName),
        nodeHost = ipAddr,
        command = commandOpt,
        context = contextMap.toMap
      )
      Some(ret)
    } catch { case t: Throwable =>
      log.ex(t, s"SMGKubeClusterProcessor.processAutoconfMap(${kubeObjectName}.${kubeObjectNamespace}): " +
        s"Context: ${contextMap}. Unexpected error: ${t.getMessage}")
      None
    }
  }

  private def processServiceConf(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                         kubeService: KubeService): Seq[SMGAutoTargetConf]  = {
   // val hasDupPortNames = kubeService.ports.map(_.portName(false)).distinct.size != kubeService.ports.size
    val nobj = kubeService

    val autoconfMaps = processAutoconfAnnotations(cConf, autoConf, nobj, kubeService.clusterIp,
      nobj.ports, nobj.annotations)
    val autoconfs = autoconfMaps.flatMap { acm =>
      processAutoconfMap(acm, cConf, autoConf.targetType, autoConf.filter,
        nobj.name, Some(nobj.namespace), nobj.labels,
        Some(kubeService.clusterIp), idxId = None,
        parentIndexId = cConf.endpointsIndexId)
    }
    autoconfs
  }

  private def processEndpointConf(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                          kubeEndpoint: KubeEndpoint): Seq[SMGAutoTargetConf] = {
    var idx: Option[Int] = None
    if (kubeEndpoint.subsets.size > 1)
      idx = Some(0)
    kubeEndpoint.subsets.flatMap { subs =>
      if ((subs.addresses.size > 1) && idx.isEmpty)
        idx = Some(0)
      subs.addresses.flatMap { addr =>
        if (idx.isDefined) idx = Some(idx.get + 1)
        val nobj = kubeEndpoint
        val autoconfMaps = processAutoconfAnnotations(cConf, autoConf, nobj, addr, subs.ports, nobj.annotations)
        val autoconfs = autoconfMaps.flatMap { acm =>
          processAutoconfMap(acm, cConf, autoConf.targetType, autoConf.filter,
            nobj.name, Some(nobj.namespace), nobj.labels,
            Some(addr), idxId = idx,
            parentIndexId = cConf.endpointsIndexId)
        }
        autoconfs
      }
    }
  }

  private def processPodsPortConfs(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                           pods: Seq[KubePod]) : Seq[SMGAutoTargetConf] = {
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
          val nobj = KubeNamedObject(pod.stableName(idx), pod.namespace, pod.labels, pod.annotations)
          val autoconfMaps = processAutoconfAnnotations(cConf, autoConf, pod, pod.podIp.getOrElse(""), pod.ports, pod.annotations)
          val autoconfs = autoconfMaps.flatMap { acm =>
            processAutoconfMap(acm, cConf, autoConf.targetType, autoConf.filter,
              nobj.name, Some(nobj.namespace), nobj.labels,
              pod.podIp, idxId = None,
              parentIndexId = cConf.podPortsIndexId)
          }
          autoconfs
        }
      }
    }
  }

  private def dumpObjList(clusterId: String, pluginId: String, lst: java.util.ArrayList[Object]): Option[String] = {
    if (!lst.isEmpty) {
      val out = new StringBuilder()
      out.append(s"# This file is automatically generated. Changes will be overwritten\n")
      out.append(s"# Generated by SMGKubePlugin for $pluginId from cluster config $clusterId.\n")
      val yaml = new Yaml()
      out.append(yaml.dump(lst))
      out.append("\n")
      Some(out.toString())
    } else None
  }

  private def getAllAutoConfs(cConf: SMGKubeClusterConf): Seq[SMGAutoTargetConf] = {
    val kubeClient = new SMGKubeClient(log, cConf.uid, cConf.authConf, cConf.defaultFetchCommandTimeout)
    try {
      // process cluster - level autoconf targets
      val clusterAutoconfs = ListBuffer[SMGAutoTargetConf]()
      cConf.clusterGlobalAutoconfs.foreach { ac =>
        val flt = ac.get("filter").map(SMGConfigParser.yobjMap).map(m => SMGFilter.fromYamlMap(m.toMap))
        val aacOpt = processAutoconfMap(ac, cConf, ConfType.global, flt, "cluster", None,
          Map(), None, None, cConf.clusterIndexId)
        if (aacOpt.isDefined)
          clusterAutoconfs += aacOpt.get
      }

      // generate autoconfs
      val nodeAutoConfs = ListBuffer[SMGAutoTargetConf]()
      kubeClient.listNodes().foreach { kubeNode =>
        val targetHost = kubeNode.ipAddress.getOrElse(kubeNode.hostName.getOrElse(kubeNode.name))
        cConf.nodeAutoconfs.foreach { ac =>
          val flt = ac.get("filter").map(SMGConfigParser.yobjMap).map(m => SMGFilter.fromYamlMap(m.toMap))
          val aacOpt = processAutoconfMap(ac, cConf, ConfType.node, flt, kubeNode.name, None,
            kubeNode.labels, Some(targetHost), None, cConf.nodesIndexId)
          if (aacOpt.isDefined)
            nodeAutoConfs += aacOpt.get
        }
      }

      val autoMetricsConfs: Seq[SMGAutoTargetConf] = cConf.autoConfs.
        filter(_.enabled).flatMap { aConf =>
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
      clusterAutoconfs.toList ++ nodeAutoConfs.toList ++ autoMetricsConfs
    } finally {
      kubeClient.close()
    }
  }

  private def getYamlText(cConf: SMGKubeClusterConf, allAutoConfs: Seq[SMGAutoTargetConf]): Option[String] = {
    val autoconfObjsLst = new java.util.ArrayList[Object]()
    allAutoConfs.foreach { atConf =>
      autoconfObjsLst.add(SMGAutoTargetConf.dumpYamlObj(atConf))
    }
    val autoconfText = dumpObjList(cConf.uid, "autoconf", autoconfObjsLst)
    autoconfText
  }

  private def replaceFile(confOutputFile: String, yamlText: String): Boolean = {
    val oldYamlText = if (Files.exists(Paths.get(confOutputFile)))
      SMGFileUtil.getFileContents(confOutputFile)
    else
      ""
    if (oldYamlText == yamlText) {
      log.debug(s"SMGKubeClusterProcessor.replaceFile(${confOutputFile}) - no config changes detected")
      return false
    }
    if (yamlText != "") {
      SMGFileUtil.outputStringToFile(confOutputFile, yamlText, None) // cConf.confOutputBackupExt???
    } else Files.delete(Paths.get(confOutputFile))
    true
  }

  case class RunResult(reloadAutoconf: Boolean)

  private def processClusterConf(cConf: SMGKubeClusterConf): RunResult = {
    // output a cluster yaml and return true if changed
    try {
      val allAutoConfs = getAllAutoConfs(cConf: SMGKubeClusterConf)
      autoconfTargetSummaries.put(cConf, allAutoConfs.map(KubeAutoconfTargetSummary).toList)
      val autoconfYamlText = getYamlText(cConf, allAutoConfs).getOrElse("")
      val autoconfOutputFile = pluginConf.autoconfTargetsD.stripSuffix(File.separator) + File.separator +
        cConf.uid + ".yml"
      val autoconfNeedReload = replaceFile(autoconfOutputFile, autoconfYamlText)

      RunResult(autoconfNeedReload)
    } catch { case t: Throwable =>
      log.ex(t, s"SMGKubeClusterProcessor.processClusterConf(${cConf.uid}): unexpected error: ${t.getMessage}")
      RunResult(reloadAutoconf = false)
    }
  }

  private def cleanupAutoconfTargets(myPluginConf: SMGKubePluginConf): Unit = {
    val liveConfs = myPluginConf.clusterConfs.toSet
    val toDel = autoconfTargetSummaries.keySet -- liveConfs
    toDel.foreach { cc =>
      autoconfTargetSummaries.remove(cc)
    }
  }

  private def cleanupAutoDiscoveryCommandsCache(): Unit = {
    val tsmsNow = System.currentTimeMillis()
    autoDiscoveryCommandsCache.toSeq.foreach { case (k, v) =>
      if (v._2 < tsmsNow){
        autoDiscoveryCommandsCache.remove(k)
      }
    }
  }

  def run(): RunResult = {
    val myConf = pluginConf
    cleanupAutoconfTargets(myConf)
    cleanupAutoDiscoveryCommandsCache()
    var ret = RunResult(reloadAutoconf = false)
    myConf.clusterConfs.foreach { cConf =>
      val cRet = processClusterConf(cConf)
      if (cRet.reloadAutoconf) {
        ret = RunResult(reloadAutoconf = true)
      }
    }
    ret
  }

  private val KUBECTL_TOP_PODS_PF_NAME = "kubectl-top-pods-pf"

  def preFetches: Seq[SMGPreFetchCmd] = pluginConfParser.conf.clusterConfs.flatMap { cConf =>
    Seq()
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
      if (cConf.indexesByType && cConf.clusterGlobalAutoconfs.nonEmpty) // top level global metrics index
        ret += myIndexDef(cConf.globalsIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Global stats",
          idxPrefix + "global.",
          myParentIndexId
        )
      if (cConf.indexesByType && cConf.nodeAutoconfs.nonEmpty) // top level node metrics index
        ret += myIndexDef(cConf.nodesIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Nodes stats",
          idxPrefix + "node.",
          myParentIndexId
        )
      if (cConf.indexesByType && cConf.autoConfs.exists(x => x.targetType == ConfType.service && x.enabled))  // top level svcs metrics index
        ret += myIndexDef(cConf.servicesIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Services stats",
          idxPrefix + "service.",
          myParentIndexId
        )
      if (cConf.indexesByType && cConf.autoConfs.exists(x => x.targetType == ConfType.endpoint && x.enabled)) // top level endpoints metrics index
        ret += myIndexDef(cConf.endpointsIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Endpoints stats",
          idxPrefix + "endpoint.",
          myParentIndexId
        )
      if (cConf.indexesByType && cConf.autoConfs.exists(x => x.targetType == ConfType.pod_port && x.enabled)) // top level podPorts metrics index
        ret += myIndexDef(cConf.podPortsIndexId.get,
          s"Kubernetes cluster ${cConf.uid} - Pods stats",
          idxPrefix + "pod_port.",
          myParentIndexId
        )
      ret.toList
    } else Seq()
  }
}
