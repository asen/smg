package com.smule.smgplugins.kube

import java.io.File
import java.nio.file.{Files, Paths}

import com.smule.smg.config.{SMGConfigParser, SMGConfigService}
import com.smule.smg.core.{SMGCmd, SMGCmdException, SMGFileUtil, SMGFilter, SMGLoggerApi}
import com.smule.smg.openmetrics.OpenMetricsStat
import com.smule.smgplugins.kube.SMGKubeClient.{KubeEndpoint, KubeNsObject, KubePort, KubeService, KubeServicePort}
import com.smule.smgplugins.scrape.SMGScrapeTargetConf
import org.yaml.snakeyaml.Yaml

import scala.collection.concurrent.TrieMap
import scala.util.Random

class SMGKubeClusterProcessor(pluginConfParser: SMGKubePluginConfParser,
                              smgConfSvc: SMGConfigService,
                              log: SMGLoggerApi) {
  private def pluginConf = pluginConfParser.conf // function - do not cache pluginConf

  private def processClusterMetricsConf(objectName: String,
                                targetHost: String,
                                targetTypeHuman: String,
                                cConf: SMGKubeClusterConf,
                                cmConf: SMGKubeClusterMetricsConf): Option[SMGScrapeTargetConf] = {
    val targetType = targetTypeHuman.toLowerCase
    val tcUid = s"${cConf.uid}.$targetType.$objectName.${cmConf.uid}"
    if (!SMGConfigParser.validateOid(tcUid)) {
      log.error(s"SMGKubeClusterProcessor - invalid SMG uid: $tcUid, ignoring metric conf")
      return None
    }
    val confOutput = s"${cConf.uid}-$targetType-$objectName-${cmConf.uid}.yml"
    val humanName = s"${cConf.hname} ${targetTypeHuman} $objectName ${cmConf.hname}"
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
      parentIndexId = cConf.parentIndexId,
      idPrefix = cConf.idPrefix,
      notifyConf = if (cmConf.notifyConf.isDefined) cmConf.notifyConf else cConf.notifyConf,
      regexReplaces = cConf.regexReplaces ++ cmConf.regexReplaces,
      labelsInUids = cmConf.labelsInUids
    )
    Some(ret)
  }

  // command -> last executed (good or bad)
  private val knownGoodServiceCommands = TrieMap[String,Long]()
  private val knownBadServiceCommands = TrieMap[String,Long]()

  private def checkAutoConf(command: String,
                            cConf: SMGKubeClusterConf,
                            autoConf: SMGKubeClusterAutoConf,
                            kubeNsObject: KubeNsObject,
                            kubePort: KubePort): Boolean = {
    // filter out invalid ports as far as we can tell
    def logSkipped(reason: String): Unit = {
      log.info(s"SMGKubeClusterProcessor.checkAutoConf(${autoConf.targetType}): ${cConf.hname} " +
        s"${kubeNsObject.namespace}.${kubeNsObject.name}: skipped due to $reason")
    }
    val lastGoodRunTs = knownGoodServiceCommands.get(command)
    if (lastGoodRunTs.isDefined) {
      // re-check occasionally?
      // TODO: right now - once good, its good until SMG restart
      // note that removed services will not even get here and will disappear automatically
      return true
    }
    val lastBadRunTs = knownBadServiceCommands.get(command)
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
      logSkipped(s"protocol=${kubePort.protocol} (not TCP)")
      knownBadServiceCommands.put(command, System.currentTimeMillis())
      return false
    }
    try {
      val out = SMGCmd(command).run().mkString("\n")
      if (OpenMetricsStat.parseText(out, log, labelsInUid = false).nonEmpty) {
        //keep known up services in a cache and not run this every minute -
        //we only want to know if it is http and has valid /metrics URL, once
        knownGoodServiceCommands.put(command, System.currentTimeMillis())
        knownBadServiceCommands.remove(command)
        true
      } else {
        logSkipped(s"command output unparse-able ($command): ${out}")
        knownBadServiceCommands.put(command, System.currentTimeMillis())
        false
      }
    } catch { case t: Throwable => //SMGCmdException =>
      logSkipped(s"command or metrics parse failed: ${t.getMessage}")
      knownBadServiceCommands.put(command, System.currentTimeMillis())
      false
    }
  }


  def processAutoPortConf(cConf: SMGKubeClusterConf,
                          autoConf: SMGKubeClusterAutoConf,
                          nsObject: KubeNsObject,
                          ipAddr: String, kubePort: KubePort): Option[SMGScrapeTargetConf] = {
    try {
      val proto = if (autoConf.useHttps) "https://" else "http://"
      val command = cConf.fetchCommand + " " + proto + ipAddr + s":${kubePort.port}/metrics"
      if (!checkAutoConf(command, cConf, autoConf, nsObject, kubePort))
        return None
      val uid = nsObject.namespace + "." + nsObject.name
      val title = s"${cConf.hname} ${autoConf.targetType} ${nsObject.name} (${nsObject.namespace})"
      val confOutput = s"${cConf.uid}-${autoConf.targetType}-${nsObject.namespace}-${nsObject.name}.yml"
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
        parentIndexId = cConf.parentIndexId,
        idPrefix = cConf.idPrefix,
        notifyConf = cConf.notifyConf,
        regexReplaces = cConf.regexReplaces ++ autoConf.regexReplaces,
        labelsInUids = false
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
      processAutoPortConf(cConf, cConf.svcConf, kubeService, kubeService.clusterIp, svcPort)
    }
  }


  def processEndpointConf(cConf: SMGKubeClusterConf, kubeEndpoint: KubeEndpoint): Seq[SMGScrapeTargetConf] = {
    // TODO check eligibility based on labels?
    kubeEndpoint.subsets.flatMap { subs =>
      subs.addresses.flatMap { addr =>
        subs.ports.flatMap { prt =>
           processAutoPortConf(cConf, cConf.endpointsConf, kubeEndpoint, addr, prt)
        }
      }
    }
  }

  def getYamlText(cConf: SMGKubeClusterConf): String = {
    val kubeClient = new SMGKubeClient(log, cConf.uid, cConf.authConf)
    try {
      // generate metrics confs
      val nodeMetricsConfs: Seq[SMGScrapeTargetConf] = cConf.nodeMetrics.flatMap { cmConf =>
        kubeClient.listNodes().flatMap { kubeNode =>
          val targetHost = kubeNode.ipAddress.getOrElse(kubeNode.hostName.getOrElse(kubeNode.name))
          processClusterMetricsConf(kubeNode.name, targetHost, "Node", cConf, cmConf)
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

      // dump
      val objsLst = new java.util.ArrayList[Object]()
      (nodeMetricsConfs ++ serviceMetricsConfs).foreach { stConf =>
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
}
