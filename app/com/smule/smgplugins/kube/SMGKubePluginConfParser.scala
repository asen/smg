package com.smule.smgplugins.kube

import java.io.File

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.{SMGFileUtil, SMGFilter, SMGLoggerApi}
import com.smule.smg.monitor.{SMGMonAlertConfSource, SMGMonNotifyConf}
import com.smule.smgplugins.scrape.RegexReplaceConf
import org.yaml.snakeyaml.Yaml

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class SMGKubePluginConfParser(pluginId: String, confFile: String, log: SMGLoggerApi) {

  private def parseClusterMetricConf(ymap: mutable.Map[String, Object]): Option[SMGKubeClusterMetricsConf] = {
    if (!ymap.contains("uid")){
      return None
    }
    val uid = ymap("uid").toString
    val notifyConf = SMGMonNotifyConf.fromVarMap(
      // first two technically unused
      SMGMonAlertConfSource.OBJ,
      pluginId + "." + uid,
      ymap.toMap.map(t => (t._1, t._2.toString))
    )

    Some(
      SMGKubeClusterMetricsConf(
        uid = uid,
        humanName = ymap.get("name").map(_.toString),
        interval = ymap.get("interval").map(_.asInstanceOf[Int]),
        portAndPath = ymap.get("port_path").map(_.toString).getOrElse("/metrics"),
        proto = ymap.get("proto").map(_.toString),
        filter = if (ymap.contains("filter")){
          Some(SMGFilter.fromYamlMap(yobjMap(ymap("filter")).toMap))
        } else None,
        regexReplaces = ymap.get("regex_replaces").map { yo: Object =>
          yobjList(yo).flatMap { o =>  RegexReplaceConf.fromYamlObject(yobjMap(o)) }
        }.getOrElse(Seq()),
        labelsInUids = false, // TODO?
        notifyConf = notifyConf)
    )
  }

  private def parseClusterConf(ymap: mutable.Map[String, Object]): Option[SMGKubeClusterConf] = {
    if (!ymap.contains("uid")){
      return None
    }
    if (!ymap.contains("fetch_command")){
      return None
    }
    val uid = ymap("uid").toString
    val notifyConf = SMGMonNotifyConf.fromVarMap(
      // first two technically unused
      SMGMonAlertConfSource.OBJ,
      pluginId + "." + uid,
      ymap.toMap.map(t => (t._1, t._2.toString))
    )
    val nodeMetrics = if (ymap.contains("node_metrcis")){
      val metricsSeq = yobjList(ymap("node_metrcis"))
      metricsSeq.flatMap { o => parseClusterMetricConf(yobjMap(o))}
    } else Seq()

    Some(
      SMGKubeClusterConf(
        uid = uid,
        humanName = ymap.get("name").map(_.toString),
        interval = ymap.get("interval").map(_.asInstanceOf[Int]).getOrElse(SMGConfigParser.defaultInterval),
        fetchCommand = ymap("fetch_command").toString,
        fetchCommandTimeout =
          ymap.get("fetch_timeout").map(_.asInstanceOf[Int]).getOrElse(SMGConfigParser.defaultTimeout),
        filter = if (ymap.contains("filter")){
          Some(SMGFilter.fromYamlMap(yobjMap(ymap("filter")).toMap))
        } else None,
        idPrefix = ymap.get("id_prefix").map(_.toString),
        regexReplaces = ymap.get("regex_replaces").map { yo: Object =>
          yobjList(yo).flatMap { o =>  RegexReplaceConf.fromYamlObject(yobjMap(o)) }
        }.getOrElse(Seq()),
        nodeMetrics = nodeMetrics,
        svcConf = SMGKubeClusterAutoConf.fromYamlMap(ymap, "service"),
        endpointsConf = SMGKubeClusterAutoConf.fromYamlMap(ymap, "endpoint"),
        parentPfId  = ymap.get("pre_fetch").map(_.toString),
        parentIndexId = ymap.get("parent_index").map(_.toString),
        notifyConf = notifyConf,
        authConf = SMGKubeClusterAuthConf.fromYamlObject(ymap),
        prefixIdsWithClusterId = ymap.get("prefix_ids_with_cluster_id").map(_.toString).getOrElse("false") == "true",
        kubectlTopStats = ymap.get("top_stats").map(_.toString).getOrElse("true") != "false",
        rraDefAgg = ymap.get("rra_agg").map(_.toString),
        rraDefDtl = ymap.get("rra_dtl").map(_.toString)
      )
    )
  }

  private def parseClustersSeq(yamlList: mutable.Seq[Object], fname: String): Seq[SMGKubeClusterConf] = {
    val ret = ListBuffer[SMGKubeClusterConf]()
    yamlList.foreach { yobj =>
      val ymap = yobjMap(yobj)
      if (ymap.contains("include")) { // process include
        if (ymap.size > 1) {
          log.warn(s"SMGKubePluginConfParser.parseConf($fname): cluster include has additional properties " +
            s"(will be ignored): ${ymap.keys.mkString(",")}")
        }
        val glob = ymap("include").toString
        val fileList = SMGConfigParser.expandGlob(glob, log)
        fileList.foreach { fname =>
          ret ++= parseClustersInclude(fname)
        }
      } else {
        val copt = parseClusterConf(ymap)
        if (copt.isDefined) {
          ret += copt.get
        } else
          log.warn(s"SMGKubePluginConfParser.parseConf($fname): ignoring invalid autoconf: ${ymap.toMap}")
      }
    }
    ret.toList
  }


  private def parseClustersInclude(fn: String): Seq[SMGKubeClusterConf] = {
    try {
      val confTxt = SMGFileUtil.getFileContents(fn)
      val yaml = new Yaml()
      val yamlList = yobjList(yaml.load(confTxt))
      parseClustersSeq(yamlList, fn)
    } catch { case t : Throwable =>
      log.ex(t, s"SMGKubePluginConfParser.parseAutoConfsInclude($fn): unexpected error: ${t.getMessage}")
      Seq()
    }
  }

  private def parseConf(): SMGKubePluginConf = {
    val confTxt = SMGFileUtil.getFileContents(confFile)
    val yaml = new Yaml()
    val yamlTopObject: Object = yaml.load(confTxt)
    val pluginConfObj = yobjMap(yamlTopObject)(pluginId)
    if (pluginConfObj == null)
      return SMGKubePluginConf.empty
    val pluginConfMap = yobjMap(pluginConfObj)
    if (!pluginConfMap.contains("clusters")){
      return SMGKubePluginConf.empty
    }
    val scrapeTargetsD = pluginConfMap.
      getOrElse("scrape_targets_d", SMGKubePluginConf.empty.scrapeTargetsD).toString
    val scrapeTargetsDFile = new File(scrapeTargetsD)
    if (!(scrapeTargetsDFile.exists() && scrapeTargetsDFile.isDirectory)){
      scrapeTargetsDFile.mkdirs()
    }
    val clusterLst = yobjList(pluginConfMap("clusters"))
    val clusterConfs = parseClustersSeq(clusterLst, confFile)
    SMGKubePluginConf(
      scrapeTargetsD = scrapeTargetsD,
      clusterConfs = clusterConfs
    )
  }
  
  private var myConf: SMGKubePluginConf = try {
    parseConf()
  } catch { case t: Throwable =>
    log.ex(t, s"SMGKubePluginConfParser.init - unexpected exception (assuming empty conf): ${t.getMessage}")
    SMGKubePluginConf.empty
  }

  def reload(): Unit = {
    try {
      myConf = parseConf()
    } catch { case t: Throwable =>
      log.ex(t, s"SMGKubePluginConfParser.reload - unexpected exception (config NOT reloaded): ${t.getMessage}")
    }
  }

  def conf: SMGKubePluginConf = myConf
}
