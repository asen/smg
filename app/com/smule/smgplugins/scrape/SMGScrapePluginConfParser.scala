package com.smule.smgplugins.scrape

import java.io.File

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.{SMGFileUtil, SMGFilter, SMGLoggerApi}
import com.smule.smg.monitor.{SMGMonAlertConfSource, SMGMonNotifyConf}
import org.yaml.snakeyaml.Yaml

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class SMGScrapePluginConfParser(pluginId: String, confFile: String, log: SMGLoggerApi) {

  private def yobjMap(yobj: Object): mutable.Map[String, Object] =
    yobj.asInstanceOf[java.util.Map[String, Object]].asScala

  private def yobjList(yobj: Object): mutable.Seq[Object] =
    yobj.asInstanceOf[java.util.List[Object]].asScala


  private def parseTargetConf(ymap: mutable.Map[String, Object]): Option[SMGScrapeTargetConf] = {
    if (!ymap.contains("uid")){
      return None
    }
    if (!ymap.contains("command")){
      return None
    }
    if (!ymap.contains("conf_output")){
      return None
    }
    val uid = ymap("uid").toString
    val notifyConf = SMGMonNotifyConf.fromVarMap(
      // first two technicall unused
      SMGMonAlertConfSource.OBJ,
      pluginId + "." + uid,
      ymap.toMap.map(t => (t._1, t._2.toString))
    )
    Some(
      SMGScrapeTargetConf(
        uid = uid,
        humanName = if (ymap.contains("name")) ymap("name").toString else ymap("uid").toString,
        command = ymap("command").toString,
        timeoutSec = ymap.get("timeout").map(_.asInstanceOf[Int]).getOrElse(SMGConfigParser.defaultTimeout),
        confOutput = ymap("conf_output").toString,
        confOutputBackupExt = ymap.get("conf_output_backup_ext").map(_.toString),
        filter = if (ymap.contains("filter")){
          SMGFilter.fromYamlMap(yobjMap(ymap("filter")).toMap)
        } else SMGFilter.matchAll,
        interval = ymap.get("interval").map(_.asInstanceOf[Int]).getOrElse(SMGConfigParser.defaultInterval),
        parentPfId  = ymap.get("pre_fetch").map(_.toString),
        parentIndexId = ymap.get("parent_index").map(_.toString),
        idPrefix = ymap.get("id_prefix").map(_.toString),
        notifyConf = notifyConf,
        regexReplaces = ymap.get("regex_replaces").map { yo: Object =>
          yobjList(yo).flatMap { o =>
            val m = yobjMap(o)
            if (m.contains("regex") && m.contains("replace"))
              Some(RegexReplaceConf(
                regex = m("regex").toString,
                replace = m("replace").toString,
                filterRegex = m.get("filterRegex").map(_.toString)
              ))
            else None
          }
        }.getOrElse(Seq())
      )
    )
  }

  private def parseTargetsSeq(yamlList: mutable.Seq[Object], fname: String): Seq[SMGScrapeTargetConf] = {
    val ret = ListBuffer[SMGScrapeTargetConf]()
    yamlList.foreach { yobj =>
      val ymap = yobjMap(yobj)
      if (ymap.contains("include")) { // process include
        if (ymap.size > 1) {
          log.warn(s"SMGScrapeConfParser.parseConf($fname): autconf include has additional properties " +
            s"(will be ignored): ${ymap.keys.mkString(",")}")
        }
        val glob = ymap("include").toString
        val fileList = SMGConfigParser.expandGlob(glob, log)
        fileList.foreach { fname =>
          ret ++= parseTargetsInclude(fname)
        }
      } else { // process auto-conf object
        val copt = parseTargetConf(ymap)
        if (copt.isDefined) {
          ret += copt.get
        } else
          log.warn(s"MGScrapeConfParser.parseConf($fname): ignoring invalid autoconf: ${ymap.toMap}")
      }
    }
    ret.toList
  }

  private def parseTargetsInclude(fn: String): Seq[SMGScrapeTargetConf] = {
    try {
      val confTxt = SMGFileUtil.getFileContents(fn)
      val yaml = new Yaml()
      val yamlList = yobjList(yaml.load(confTxt))
      parseTargetsSeq(yamlList, fn)
    } catch { case t : Throwable =>
      log.ex(t, s"SMGScrapeConfParser.parseAutoConfsInclude($fn): unexpected error: ${t.getMessage}")
      Seq()
    }
  }

  private def parseConf(): SMGScrapePluginConf = {
    val confTxt = SMGFileUtil.getFileContents(confFile)
    val yaml = new Yaml()
    val yamlTopObject: Object = yaml.load(confTxt)
    val pluginConfObj = yobjMap(yamlTopObject)(pluginId)
    if (pluginConfObj == null)
      return SMGScrapePluginConf(targets = Seq(), None, confOutputDirOwned = false)
    val pluginConfMap = yobjMap(pluginConfObj)
    val targetConfs = if (pluginConfMap.contains("targets")){
      val yamlList = yobjList(pluginConfMap("targets"))
      parseTargetsSeq(yamlList, confFile)
    } else Seq()
    val confOutputDir = pluginConfMap.get("conf_output_dir").map(_.toString)
    val confOutputDirOwned = pluginConfMap.get("conf_output_dir_owned").exists(_.toString == "true")
    if (confOutputDir.isDefined && confOutputDirOwned){
      val dirFile = new File(confOutputDir.get)
      if (!dirFile.exists())
        dirFile.mkdirs() // this would throw if there is fs/permissions issue and reject the conf
    }
    SMGScrapePluginConf(
      targetConfs,
      confOutputDir,
      confOutputDirOwned
    )
  }

  private var myConf: SMGScrapePluginConf = try {
    parseConf()
  } catch { case t: Throwable =>
    log.ex(t, s"SMGScrapeConfParser.init - unexpected exception (assuming empty conf): ${t.getMessage}")
    SMGScrapePluginConf(targets = Seq(), confOutputDir = None, confOutputDirOwned = false)
  }

  def reload(): Unit = {
    try {
      myConf = parseConf()
    } catch { case t: Throwable =>
      log.ex(t, s"SMGScrapeConfParser.reload - unexpected exception (config NOT reloaded): ${t.getMessage}")
    }
  }

  def conf: SMGScrapePluginConf = myConf
}
