package com.smule.smgplugins.kube

import com.smule.smg.config.{SMGConfIndex, SMGConfigParser, SMGConfigService}
import com.smule.smg.core._
import com.smule.smg.openmetrics.OpenMetricsParser
import com.smule.smgplugins.autoconf.SMGAutoTargetConf
import com.smule.smgplugins.kube.SMGKubeClient._
import com.smule.smgplugins.kube.SMGKubeClusterAutoConf.ConfType
import com.smule.smgplugins.scrape.{OpenMetricsResultData, SMGScrapeTargetConf}
import org.yaml.snakeyaml.Yaml

import java.io.File
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Random, Try}

class SMGKubeClusterProcessor(pluginConfParser: SMGKubePluginConfParser,
                              smgConfSvc: SMGConfigService,
                              log: SMGLoggerApi) {
  private def pluginConf = pluginConfParser.conf // function - do not cache pluginConf

  private def processNodeMetricsConf(
                                      objectName: String,
                                      targetHost: String,
                                      cConf: SMGKubeClusterConf,
                                      cmConf: SMGKubeNodeMetricsConf,
                                      parentIndexId: Option[String]
                                    ): Option[SMGScrapeTargetConf] = {
    val targetType = "node"
    val tcUid = s"${cConf.uidPrefix}$targetType.$objectName.${cmConf.uid}"
    if (!SMGConfigParser.validateOid(tcUid)) {
      log.error(s"SMGKubeClusterProcessor - invalid SMG uid: $tcUid, ignoring metric conf")
      return None
    }
    val flt = if (cmConf.filter.isDefined) cmConf.filter else cConf.filter
    if (flt.isDefined && !flt.get.matchesId(tcUid)){
      log.debug(s"SMGKubeClusterProcessor - fliter does not match - SMG uid: $tcUid, ignoring metric conf")
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
      rraDef = cConf.rraDef,
      needParse = cConf.needParse,
      sortIndexes = cmConf.sortIndexes
    )
    Some(ret)
  }

  private val autoDiscoveryCache = new CommandAutoDiscoveryCache(log)

  def listAutoDiscoveredCommands: Seq[AutoDiscoveredCommandStatus] =
    autoDiscoveryCache.listAutoDiscoveredCommands

  private def logSkipped(namespace: String, name: String, targetType: String,
                         clusterUid: String, reason: String): Unit = {
    val msg = s"SMGKubeClusterProcessor.checkAutoConf(${targetType}): ${clusterUid} " +
      s"${namespace}${name}: skipped due to $reason"
    if (pluginConf.logSkipped)
      log.info(msg)
    else
      log.debug(msg)
  }

  private def logSkipped(kubeNsObject: KubeNsObject, targetType: String,
                         clusterUid: String, reason: String): Unit = {
    logSkipped(kubeNsObject.namespace, kubeNsObject.name, targetType, clusterUid, reason)
  }

  private def checkMetricsAutoConfCommand(
                                           command: String,
                                           cConf: SMGKubeClusterConf,
                                           autoConf: SMGKubeClusterAutoConf,
                                           kubeNsObject: KubeNsObject,
                                           kubePort: KubePort
                                         ): Boolean = {
    // filter out invalid ports as far as we can tell
    def logSkipped(reason: String): Unit = this.logSkipped(kubeNsObject, autoConf.targetType.toString, cConf.uid, reason)
    val lastGoodRunTs = autoDiscoveryCache.lastGoodRunTs(command)
    if (lastGoodRunTs.isDefined) {
      // re-check occasionally?
      // TODO: right now - once good, its good until SMG restart
      // note that removed services will not even get here and will disappear automatically
      return true
    }
    val lastBadRunTs = autoDiscoveryCache.lastBadRunTs(command)
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
      autoDiscoveryCache.recordBad(command, kubeNsObject, reason)
      return false
    }
    try {
      val outObj = smgConfSvc.runFetchCommand(SMGCmd(command, cConf.fetchCommandTimeout), None)
      if (cConf.needParse) {
        val out = outObj.asStr
        if (OpenMetricsParser.parseText(out, Some(log)).nonEmpty) {
          //keep known up services in a cache and not run this every minute -
          //we only want to know if it is http and has valid /metrics URL, once
          autoDiscoveryCache.recordGood(command, kubeNsObject)
          true
        } else {
          val reason = s"command output unparse-able ($command): ${out}"
          logSkipped(reason)
          autoDiscoveryCache.recordBad(command, kubeNsObject, reason)
          false
        }
      } else {
        if (outObj.data.asInstanceOf[OpenMetricsResultData].stats.nonEmpty){
          autoDiscoveryCache.recordGood(command, kubeNsObject)
          true
        } else {
          val reason = s"command output parsed but empty ($command)"
          logSkipped(reason)
          autoDiscoveryCache.recordBad(command, kubeNsObject, reason)
          false
        }
      }
    } catch { case t: Throwable => //SMGCmdException =>
      val reason = s"command or metrics parse failed: ${t.getMessage}"
      logSkipped(reason)
      autoDiscoveryCache.recordBad(command, kubeNsObject, reason)
      false
    }
  }

  private def checkMetricsAutoConf(
                                    commands: Seq[String],
                                    cConf: SMGKubeClusterConf,
                                    autoConf: SMGKubeClusterAutoConf,
                                    kubeNsObject: KubeNsObject,
                                    kubePort: KubePort
                                  ): Option[Int] = {
    val ret = commands.indexWhere { cmd =>
      checkMetricsAutoConfCommand(cmd, cConf, autoConf, kubeNsObject, kubePort)
    }
    if (ret < 0)
      None
    else
      Some(ret)
  }

  def processMetricsAutoPortConf(
                                  cConf: SMGKubeClusterConf,
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
          logSkipped(nsObject, autoConf.targetType.toString, cConf.uid,
            s"filter not matching id: $uid")
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
        checkMetricsAutoConf(commands, cConf, autoConf, nsObject, kubePort)
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
        labelsInUids = autoConf.labelsInUids,
        extraLabels = Map("smg_target_type"-> autoConf.targetType.toString,
          "smg_target_host"-> ipAddr,
          "smg_target_port" -> kubePort.port.toString) ++ nsObject.labels,
        rraDef = cConf.rraDef,
        needParse = cConf.needParse,
        sortIndexes = autoConf.sortIndexes
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
          val kp = ports.find(p => opt.contains(p.port))
          if (kp.isDefined)
            Seq(kp.get)
          else {
            val skp = opt.map(pn => KubeSyntheticPort(pn, "TCP", None))
            Seq(skp).flatten
          }
        } else ports
        (myPorts, myPath)
      } else {
        log.debug(s"SMGKubeClusterProcessor.processMetricsLabels(${cConf.uid}): " +
          s"${nobj.getClass.getName} ${nobj.name} skipped due to " +
          s"metricsEnableAnnotation=${autoConf.metricsEnableAnnotation.get}")
        (Seq(), myPath)
      }
    } else { //no annotation
      (ports, myPath)
    }
  }

  private val SEQ_TYPE_PREFIX = "_seq-"
  private val MAP_TYPE_PREFIX = "_map-"
  // smg.autoconf-0/template: blah
  // smg.autoconf-0/fs_label_filters/_list-a: "device!=tmpfs"
  // smg.autoconf-0/fs_label_filters/_list-b: "mountpoint!=/boot"
  // smg.autoconf-0/net_dvc_filters/_map-my_key: "my value"
  // smg.autoconf-0/net_dvc_filters/_map-my_other_key: "my other value"
  def getAutoconfAnnotationGroup(tseq: Seq[(String, String)]): Map[String,Object] = {
    val retStrings = mutable.Map[String, String]()
    val retSeqs = mutable.Map[String, mutable.Map[String, String]]()
    val retMaps = mutable.Map[String, mutable.Map[String, String]]()
    tseq.map { case (k,v) =>
      val myK = k.split("/",2).lift(1).getOrElse("")
      (myK, v)
    }.foreach { case (myk,v) =>
      val arr = myk.split("/", 2)
      val mytyp = arr.lift(1).getOrElse("")
      if (mytyp.startsWith(SEQ_TYPE_PREFIX)){
        val seqName = arr(0)
        val seqIndex = mytyp.stripPrefix(SEQ_TYPE_PREFIX)
        val seqBuf = retSeqs.getOrElseUpdate(seqName, mutable.Map())
        seqBuf.put(seqIndex, v)
      } else if (mytyp.startsWith(MAP_TYPE_PREFIX)){
        val mapName = arr(0)
        val mapKey = mytyp.stripPrefix(MAP_TYPE_PREFIX)
        val mapBuf = retMaps.getOrElseUpdate(mapName, mutable.Map())
        mapBuf.put(mapKey, v)
      } else {
        retStrings.put(myk, v)
      }
    }
    retStrings.toMap ++ retSeqs.map { case (k,v) =>
      // sort the "index" -> "value" map by index and get the values
      (k, v.toSeq.sortBy(_._1).map(_._2).toList)
    }.toMap ++ retMaps.map { case (k,v) =>
      (k,v.toMap)
    }.toMap
  }

  def getAutoconfAnnotationGropus(annotations: Map[String,String], prefix: String): Seq[Map[String,Object]] = {
    val myAnnotationGroups = annotations.filter{ t =>
      t._1.startsWith(prefix)
    }.toSeq.groupBy { t =>
      t._1.split("/",2)(0)
    }
    myAnnotationGroups.toSeq.map { case (_, tseq) =>
      getAutoconfAnnotationGroup(tseq)
    }
  }

  def expandAutoconfAnnotationPorts(ports: Seq[KubePort],
                                    annotationsMap: Map[String,Object]): Seq[Map[String,Object]] = {
    val template = annotationsMap.get("template").map(_.toString)
    if (template.isEmpty)
      return Seq()
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
        "port" -> port._1,
        "port_name" -> (if (hasDupPortNames) port._1 else port._2)
      )
    }
  }

  def processAutoconfAnnotations(
                                  ports: Seq[KubePort],
                                  nobjAnnotations: Map[String,String],
                                  autoconfAnnotationsPrefix: String
                                ): Seq[Map[String, Object]] = {
    val myAutoconfGroups = getAutoconfAnnotationGropus(nobjAnnotations, autoconfAnnotationsPrefix)
    myAutoconfGroups.flatMap { annotationsMap =>
      expandAutoconfAnnotationPorts(ports, annotationsMap)
    }
  }

  private def processAutoconfMap(
                          autoconfMap: Map[String, Object],
                          cConf: SMGKubeClusterConf,
                          targetType: String,
                          filter: Option[SMGFilter],
                          kubeObjectName: String,
                          kubeObjectNamespace: Option[String],
                          kubeObjectLabels: Map[String,String],
                          ipAddr: String,
                          idxId: Option[Int],
                          parentIndexId: Option[String]
                        ): Option[SMGAutoTargetConf] = {

    val namespaceStr = kubeObjectNamespace.map(_ + ".").getOrElse("")
    val namespaceFnStr = kubeObjectNamespace.map(_ + "-").getOrElse("")
    val contextMap = mutable.Map[String,Object]()
    contextMap ++= autoconfMap
    try {
      val template = contextMap("template").toString
      val templateAlias = contextMap.get("template_alias").map(_.toString).getOrElse(template)
      val staticOutput = contextMap.remove("output").map(_.toString)
      val staticUid = contextMap.remove("uid").map(_.toString)
      val staticNodeName = contextMap.remove("node_name").map(_.toString)
      val staticResolveName = contextMap.get("resolve_name").map(_.toString)
      val staticRegenDelay = contextMap.get("regen_delay").flatMap(x => Try(x.toString.toInt).toOption)

      val commandOpt = contextMap.remove("command").map(_.toString)
      val runtimeDataOpt = contextMap.get("runtime_data").map(_.toString)
      val runtimeDataTimeoutSecOpt = contextMap.get("runtime_data_timeout_sec").flatMap(s => Try(s.toString.toInt).toOption)
      val portNameOpt = contextMap.get("port_name").map(_.toString)
      val portDotName = portNameOpt.map("." + _).getOrElse("")
      val portDashName = portNameOpt.map("-" + _).getOrElse("")
      val portColName = portNameOpt.map(": " + _).getOrElse("")

      contextMap.put("node_host", ipAddr)
      contextMap.put("kube_host", ipAddr)
      contextMap.put("id_prefix", cConf.uidPrefix)
      contextMap.put("kube_prefix", cConf.uidPrefix)
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
      if (filter.isDefined){
        val flt = filter.get
        if (!flt.matchesId(uid)){
          logSkipped(namespaceStr, kubeObjectName, targetType.toString, cConf.uid,
            s"filter not matching id: $uid")
          return None
        }
      }
      val confOutput = staticOutput.getOrElse(s"${cConf.uid}-${targetType}-${namespaceFnStr}" +
        s"${kubeObjectName}${portDashName}${idxId.map(x => s"-$x").getOrElse("")}-${template}.yml")

      val portLabelMap = portNameOpt.map(n => Map("smg_target_port" -> n)).getOrElse(Map())
      val extraLabels = Map("smg_target_type"-> targetType,
        "smg_target_host"-> ipAddr) ++ portLabelMap ++ kubeObjectLabels
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

  def processServiceConf(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                         kubeService: KubeService): Seq[Either[SMGScrapeTargetConf,SMGAutoTargetConf]]  = {
    val hasDupPortNames = kubeService.ports.map(_.portName(false)).distinct.size != kubeService.ports.size
    val nobj = kubeService

    val autoconfMaps = processAutoconfAnnotations(kubeService.ports, nobj.annotations, autoConf.autoconfAnnotationsPrefix)
    val autoconfs = autoconfMaps.flatMap { acm =>
      processAutoconfMap(acm, cConf, autoConf.targetType.toString, autoConf.filter,
        nobj.name, Some(nobj.namespace), nobj.labels,
        kubeService.clusterIp, idxId = None,
        parentIndexId = cConf.endpointsIndexId)
    }.map(Right(_))

    val (ports, path) = processMetricsAnnotations(kubeService.ports, nobj, cConf, autoConf)
    val scrapeConfs = ports.flatMap { prt =>
      processMetricsAutoPortConf(cConf, autoConf, nobj,
        kubeService.clusterIp, prt, metricsPath = path, idxId = None,
        parentIndexId = cConf.endpointsIndexId, forcePortNums = hasDupPortNames)
    }.map(Left(_))
    autoconfs ++ scrapeConfs
  }

  def processEndpointConf(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                          kubeEndpoint: KubeEndpoint): Seq[Either[SMGScrapeTargetConf,SMGAutoTargetConf]] = {
    var idx: Option[Int] = None
    if (kubeEndpoint.subsets.size > 1)
      idx = Some(0)
    kubeEndpoint.subsets.flatMap { subs =>
      if ((subs.addresses.size > 1) && idx.isEmpty)
        idx = Some(0)
      subs.addresses.flatMap { addr =>
        if (idx.isDefined) idx = Some(idx.get + 1)
        val nobj = kubeEndpoint
        val autoconfMaps = processAutoconfAnnotations(subs.ports, nobj.annotations, autoConf.autoconfAnnotationsPrefix)
        val autoconfs = autoconfMaps.flatMap { acm =>
          processAutoconfMap(acm, cConf, autoConf.targetType.toString, autoConf.filter,
            nobj.name, Some(nobj.namespace), nobj.labels,
            addr, idxId = idx,
            parentIndexId = cConf.endpointsIndexId)
        }.map(Right(_))

        val hasDupPortNames = subs.ports.map(_.portName(false)).distinct.size != subs.ports.size
        val (ports, path) = processMetricsAnnotations(subs.ports, nobj, cConf, autoConf)
        val scrapeConfs = ports.flatMap { prt =>
          processMetricsAutoPortConf(cConf, autoConf, nobj,
            addr, prt, metricsPath = path, idxId = idx,
            parentIndexId = cConf.endpointsIndexId, forcePortNums = hasDupPortNames)
        }.map(Left(_))
        autoconfs ++ scrapeConfs
      }
    }
  }

  def processPodsPortConfs(cConf: SMGKubeClusterConf, autoConf: SMGKubeClusterAutoConf,
                           pods: Seq[KubePod]) : Seq[Either[SMGScrapeTargetConf,SMGAutoTargetConf]] = {
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
          val autoconfMaps = processAutoconfAnnotations(pod.ports, pod.annotations, autoConf.autoconfAnnotationsPrefix)
          val autoconfs = autoconfMaps.flatMap { acm =>
            processAutoconfMap(acm, cConf, autoConf.targetType.toString, autoConf.filter,
              nobj.name, Some(nobj.namespace), nobj.labels,
              pod.podIp.get, idxId = None,
              parentIndexId = cConf.podPortsIndexId)
          }.map(Right(_))
          // check for dup port names
          val hasDupPortNames =
            pod.ports.map(_.portName(false)).distinct.lengthCompare(pod.ports.size) != 0
          val (ports, path) = processMetricsAnnotations(pod.ports, pod, cConf, autoConf)
          val scrapeConfs = ports.flatMap { podPort =>
            processMetricsAutoPortConf(cConf, autoConf, nobj,
              pod.podIp.get, podPort, metricsPath = path, idxId = None,
              parentIndexId = cConf.podPortsIndexId, forcePortNums = hasDupPortNames)
          }.map(Left(_))
          autoconfs ++ scrapeConfs
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
      rraDef = cConf.rraDef,
      needParse = cConf.needParse,
      sortIndexes = false
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
      rraDef = cConf.rraDef,
      needParse = cConf.needParse,
      sortIndexes = false
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
      rraDef = cConf.rraDef,
      needParse = cConf.needParse,
      sortIndexes = false
    )
    ret.toList
  }

  def dumpObjList(clusterId: String, pluginId: String, lst: java.util.ArrayList[Object]): Option[String] = {
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

  def getYamlText(cConf: SMGKubeClusterConf): (Option[String], Option[String]) = {
    val kubeClient = new SMGKubeClient(log, cConf.uid, cConf.authConf, cConf.fetchCommandTimeout)
    try {
      // generate SMGScrapeTargetConf from topNodes and topPods
      val topStats = processKubectlTopStats(cConf)
      // generate metrics confs and autoconfs
      val nodeMetricsConfs = ListBuffer[SMGScrapeTargetConf]()
      val nodeAutoConfs = ListBuffer[SMGAutoTargetConf]()
      kubeClient.listNodes().foreach { kubeNode =>
        val targetHost = kubeNode.ipAddress.getOrElse(kubeNode.hostName.getOrElse(kubeNode.name))
        cConf.nodeMetrics.foreach { cmConf =>
          processNodeMetricsConf(kubeNode.name, targetHost, cConf,
            cmConf, cConf.nodesIndexId).foreach { c =>
            nodeMetricsConfs += c
          }
        }
        cConf.nodeAutoconfs.foreach { ac =>
          val flt = ac.get("filter").map(SMGConfigParser.yobjMap).map(m => SMGFilter.fromYamlMap(m.toMap))
          val aacOpt = processAutoconfMap(ac, cConf, "node", flt, kubeNode.name, None,
            kubeNode.labels, targetHost, None, cConf.nodesIndexId)
          if (aacOpt.isDefined)
            nodeAutoConfs += aacOpt.get
        }
      }
      cConf.nodeMetrics.flatMap { cmConf =>
        kubeClient.listNodes().flatMap { kubeNode =>
          val targetHost = kubeNode.ipAddress.getOrElse(kubeNode.hostName.getOrElse(kubeNode.name))
          processNodeMetricsConf(kubeNode.name, targetHost, cConf, cmConf, cConf.nodesIndexId)
        }
      }
      val autoMetricsConfs: Seq[Either[SMGScrapeTargetConf,SMGAutoTargetConf]] = cConf.autoConfs.
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
      // dump
      val scrapeObjsLst = new java.util.ArrayList[Object]()
      // TODO need to insert indexes ?
      (topStats ++ nodeMetricsConfs ++ autoMetricsConfs.flatMap(x => x.left.toOption)).foreach { stConf =>
        scrapeObjsLst.add(SMGScrapeTargetConf.dumpYamlObj(stConf))
      }
      val scrapeText = dumpObjList(cConf.uid, "scrape", scrapeObjsLst)

      val autoconfObjsLst = new java.util.ArrayList[Object]()
      (nodeAutoConfs.toList ++ autoMetricsConfs.flatMap(x => x.right.toOption)).foreach { atConf =>
        autoconfObjsLst.add(SMGAutoTargetConf.dumpYamlObj(atConf))
      }
      val autoconfText = dumpObjList(cConf.uid, "autoconf", autoconfObjsLst)

      (scrapeText, autoconfText)
    } finally {
      kubeClient.close()
    }
  }

  def replaceFile(confOutputFile: String, yamlText: String): Boolean = {
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

  case class RunResult(reloadScrape: Boolean, reloadAtoconf: Boolean)

  def processClusterConf(cConf: SMGKubeClusterConf): RunResult = {
    // output a cluster yaml and return true if changed
    try {
      val scrapeAndAutoconfYamls = getYamlText(cConf)
      val scrapeYamlText = scrapeAndAutoconfYamls._1.getOrElse("")
      val confOutputFile = pluginConf.scrapeTargetsD.stripSuffix(File.separator) + (File.separator) +
        cConf.uid + ".yml"
      val scrapeNeedReload = replaceFile(confOutputFile, scrapeYamlText)

      val autoconfYamlText = scrapeAndAutoconfYamls._2.getOrElse("")
      val autoconfOutputFile = pluginConf.autoconfTargetsD.stripSuffix(File.separator) + File.separator +
        cConf.uid + ".yml"
      val autoconfNeedReload = replaceFile(autoconfOutputFile, autoconfYamlText)

      RunResult(scrapeNeedReload, autoconfNeedReload)
    } catch { case t: Throwable =>
      log.ex(t, s"SMGKubeClusterProcessor.processClusterConf(${cConf.uid}): unexpected error: ${t.getMessage}")
      RunResult(reloadScrape = false, reloadAtoconf = false)
    }
  }

  def run(): RunResult = {
    autoDiscoveryCache.cleanupOld(SMGKubeClusterAutoConf.defaultRecheckBackoff * 2)
    var ret = RunResult(reloadScrape = false,reloadAtoconf = false)
    pluginConf.clusterConfs.foreach { cConf =>
      val cRet = processClusterConf(cConf)
      if (cRet.reloadScrape) {
        ret = RunResult(reloadScrape = true, reloadAtoconf = ret.reloadAtoconf)
      }
      if (cRet.reloadAtoconf) {
        ret = RunResult(ret.reloadScrape, reloadAtoconf = true)
      }
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
