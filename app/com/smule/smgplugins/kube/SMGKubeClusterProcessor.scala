package com.smule.smgplugins.kube

import java.io.File
import java.nio.file.{Files, Paths}

import com.smule.smg.config.{SMGConfigParser, SMGConfigService}
import com.smule.smg.core.{SMGCmd, SMGCmdException, SMGFileUtil, SMGFilter, SMGLoggerApi}
import com.smule.smgplugins.kube.SMGKubeClient.{KubeService, KubeServicePort}
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

  // command -> last successfully executed
  private val knownGoodServiceCommands = TrieMap[String,Long]()
  private val knownBadServiceCommands = TrieMap[String,Long]()

  private def checkService(command: String,
                           cConf: SMGKubeClusterConf,
                           kubeService: KubeService,
                           svcPort: KubeServicePort): Boolean = {
    // filter out invalid ports as far as we can tell
    def logSkipped(reason: String): Unit = {
      log.debug(s"SMGKubeClusterProcessor.checkService: ${cConf.hname} " +
        s"${kubeService.namespace}.${kubeService.name}: skipped due to $reason")
    }
    if (svcPort.protocol != "TCP") {
      logSkipped(s"protocol=${svcPort.protocol} (not TCP)")
      return false
    }
    val lastGoodRunTs = knownGoodServiceCommands.get(command)
    if (lastGoodRunTs.isDefined) {
      // re-check occasionally?
      return true
    }
    val lastBadRunTs = knownBadServiceCommands.get(command)
    if (lastBadRunTs.isDefined){
      val randomizedBackoff = cConf.svcConf.reCheckBackoff +
        Random.nextInt(cConf.svcConf.reCheckBackoff.toInt).toLong
      if (System.currentTimeMillis() - lastBadRunTs.get < randomizedBackoff) {
        logSkipped(s"known bad command: $command")
        return false
      }
    }
    try {
      SMGCmd(command).run()
      //keep known up services in a cache and not run this every minute -
      //we only want to know if it is http and has valid /metrics URL, once
      knownGoodServiceCommands.put(command, System.currentTimeMillis())
      knownBadServiceCommands.remove(command)
      true
    } catch { case t: SMGCmdException =>
      logSkipped(s"command failed: ${t.getMessage}")
      knownBadServiceCommands.put(command, System.currentTimeMillis())
      false
    }
  }

  def processServicePortConf(cConf: SMGKubeClusterConf,
                             kubeService: KubeService,
                             svcPort: KubeServicePort): Option[SMGScrapeTargetConf] = {
    try {
      val command = cConf.fetchCommand + " " + kubeService.clusterIp + s"${svcPort.port}/metrics"
      if (!checkService(command, cConf, kubeService, svcPort))
        return None
      val uid = kubeService.namespace + "." + kubeService.name
      val title = s"${kubeService.name} (${kubeService.namespace})"
      val confOutput = s"${cConf.uid}-svc-${kubeService.namespace}-${kubeService.name}.yml"
      val ret = SMGScrapeTargetConf(
        uid = uid,
        humanName = title,
        command = command,
        timeoutSec = cConf.fetchCommandTimeout,
        confOutput = confOutput,
        confOutputBackupExt = None,
        filter = if (cConf.svcConf.filter.isDefined) cConf.svcConf.filter else cConf.filter,
        interval = cConf.interval,
        parentPfId = cConf.parentPfId,
        parentIndexId = cConf.parentIndexId,
        idPrefix = cConf.idPrefix,
        notifyConf = cConf.notifyConf,
        regexReplaces = cConf.regexReplaces ++ cConf.svcConf.regexReplaces,
        labelsInUids = false
      )
      Some(ret)
    } catch { case t: Throwable =>
      log.ex(t, s"SMGKubeClusterProcessor.processServicePortConf(${kubeService.name},${svcPort.port}): " +
        s"Unexpected error: ${t.getMessage}")
      None
    }
  }

  def processServiceConf(cConf: SMGKubeClusterConf, kubeService: KubeService): Seq[SMGScrapeTargetConf] = {
    // TODO check eligibility based on labels?
    kubeService.ports.flatMap(svcPort => processServicePortConf(cConf, kubeService, svcPort))
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
