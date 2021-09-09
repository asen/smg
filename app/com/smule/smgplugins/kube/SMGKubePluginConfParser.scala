package com.smule.smgplugins.kube

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.{SMGFileUtil, SMGFilter, SMGLoggerApi}
import com.smule.smg.monitor.SMGMonAlertConfSource
import com.smule.smg.notify.SMGMonNotifyConf
import com.smule.smgplugins.scrape.RegexReplaceConf
import org.yaml.snakeyaml.Yaml

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

class SMGKubePluginConfParser(pluginId: String, confFile: String, log: SMGLoggerApi) {

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
    val clusterGlobalAutoconfs = ListBuffer[Map[String, Object]]()
    if (ymap.contains("cluster")) {
      yobjList(ymap("cluster")).foreach { o =>
        clusterGlobalAutoconfs += yobjMap(o).toMap
      }
    }
    //val nodeMetrics = ListBuffer[SMGKubeNodeMetricsConf]()
    val nodeAutoconfs = ListBuffer[Map[String, Object]]()
    if (ymap.contains("node_metrics")){
      val metricsSeq = yobjList(ymap("node_metrics"))
      metricsSeq.foreach { o =>
        val m = yobjMap(o)
        // scrape plugin would expect a port_path param, convert this to separate port and path params
        val portPathMap: Map[String, Object] = if(m.contains("port_path")) {
          val ppArr = m("port_path").toString.stripPrefix(":").split("/", 2)
          val ppPort = if (ppArr(0) == "") None else Try(ppArr(0).toInt).toOption
          val ppPath = ppArr.lift(1)
          (if (!m.contains("port") && ppPort.isDefined) Map("port" -> Integer.valueOf(ppPort.get)) else Map()) ++
            (if (!m.contains("path") && ppPath.isDefined) Map("path" -> ppPath.get) else Map())
        } else Map()
        nodeAutoconfs += (m.toMap ++ portPathMap)
      }
    }

    val autoConfs = if (ymap.contains("auto_confs")){
      val seq = yobjList(ymap("auto_confs"))
      seq.flatMap { o =>
        SMGKubeClusterAutoConf.fromYamlMap(yobjMap(o), log)
      }
    } else Seq()
    val rraDef: Option[String] = if (ymap.contains("rra_def"))
      ymap.get("rra_def").map(_.toString)
    else
      ymap.get("rra_agg").map(_.toString) // backwards compatible
    Some(
      SMGKubeClusterConf(
        uid = uid,
        humanName = ymap.get("name").map(_.toString),
        interval = ymap.get("interval").map(_.asInstanceOf[Int]).getOrElse(SMGConfigParser.defaultInterval),
        defaultFetchCommand = ymap("fetch_command").toString,
        defaultFetchCommandTimeout =
          ymap.get("fetch_timeout").map(_.asInstanceOf[Int]).getOrElse(SMGConfigParser.defaultTimeout),
        filter = if (ymap.contains("filter")){
          Some(SMGFilter.fromYamlMap(yobjMap(ymap("filter")).toMap))
        } else None,
        defaultTemplate = ymap.getOrElse("default_template", "openmetrics").toString,
        idPrefix = ymap.get("id_prefix").map(_.toString),
        regexReplaces = ymap.get("regex_replaces").map { yo: Object =>
          yobjList(yo).flatMap { o =>  RegexReplaceConf.fromYamlObject(yobjMap(o)) }
        }.getOrElse(Seq()),
        // nodeMetrics = nodeMetrics.toList,
        clusterGlobalAutoconfs = clusterGlobalAutoconfs.toList,
        nodeAutoconfs = nodeAutoconfs.toList,
        autoConfs = autoConfs,
        parentPfId  = ymap.get("pre_fetch").map(_.toString),
        parentIndexId = ymap.get("parent_index").map(_.toString),
        notifyConf = notifyConf,
        authConf = SMGKubeClusterAuthConf.fromYamlObject(ymap),
        prefixIdsWithClusterId = ymap.get("prefix_ids_with_cluster_id").map(_.toString).
          getOrElse("false") == "true",
        //kubectlTopStats = ymap.get("top_stats").map(_.toString).getOrElse("true") != "false",
        rraDef = rraDef,
        needParse = ymap.getOrElse("need_parse", "true").toString != "false"
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

  private def createDirIfNotThere(dir: String): Unit = {
    val dirF = new File(dir)
    if (!dirF.exists()){
      dirF.mkdirs()
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
    createDirIfNotThere(scrapeTargetsD)
    val autoconfTargetsD = pluginConfMap.
      getOrElse("autoconf_targets_d", SMGKubePluginConf.empty.autoconfTargetsD).toString
    createDirIfNotThere(autoconfTargetsD)
    val clusterLst = yobjList(pluginConfMap("clusters"))
    val clusterConfs = parseClustersSeq(clusterLst, confFile)
    val logSkipped = pluginConfMap.getOrElse("log_skipped", "false").toString == "true"
    SMGKubePluginConf(
      scrapeTargetsD = scrapeTargetsD,
      autoconfTargetsD = autoconfTargetsD,
      clusterConfs = clusterConfs,
      logSkipped = logSkipped
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
