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

  private def yobjMap(yobj: Object): mutable.Map[String, Object] = SMGScrapeTargetConf.yobjMap(yobj)

  private def yobjList(yobj: Object): mutable.Seq[Object] = SMGScrapeTargetConf.yobjList(yobj)
  
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
        val copt = SMGScrapeTargetConf.fromYamlObj(ymap)
        if (copt.isDefined) {
          ret += copt.get
        } else
          log.warn(s"SMGcrapeConfParser.parseConf($fname): ignoring invalid autoconf: ${ymap.toMap}")
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
