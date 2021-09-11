package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.{SMGFileUtil, SMGLoggerApi}
import org.yaml.snakeyaml.Yaml

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class SMGAutoConfPluginConfParser(pluginId: String, confFile: String, log: SMGLoggerApi) {

  private val BUILTIN_TEMPLATE_DIRS = Seq("smgconf/ac-templates")

  private def parseTargetsSeq(yamlList: mutable.Seq[Object],
                              fname: String,
                              outputFiles: mutable.Set[String],
                              confOutputDir: Option[String]
                             ): (Seq[SMGAutoTargetConf], Seq[SMGAutoTargetStatus]) = {
    val ret = ListBuffer[SMGAutoTargetConf]()
    val failedRet = ListBuffer[SMGAutoTargetStatus]()
    yamlList.foreach { yobj =>
      val ymap = yobjMap(yobj)
      if (ymap.contains("include")) { // process include
        if (ymap.size > 1) {
          log.warn(s"SMGAutoConfPluginConfParser.parseConf($fname): autconf include has additional properties " +
            s"(will be ignored): ${ymap.keys.mkString(",")}")
        }
        val glob = ymap("include").toString
        val fileList = SMGConfigParser.expandGlob(glob, log)
        fileList.foreach { fname =>
          val parseRet = parseTargetsInclude(fname, outputFiles, confOutputDir)
          ret ++= parseRet._1
          failedRet ++= parseRet._2
        }
      } else { // process auto-conf object
        val confOrStatus = SMGAutoTargetConf.fromYamlObj(ymap, log)
        if (confOrStatus.isLeft) {
          val copt = confOrStatus.left
          val outFn = copt.get.confOutputFile(confOutputDir)
          if (!outputFiles.contains(outFn)){
            outputFiles += outFn
            ret += copt.get
          } else {
            failedRet += SMGAutoTargetStatus(copt.get, Some("Error - duplicate output filename"))
            log.error(s"SMGAutoConfPluginConfParser.parseConf($fname): ignoring autoconf with " +
              s"duplicate output filename ($outFn): ${ymap.toMap}")
          }
        } else {
          log.error(s"SMGAutoConfPluginConfParser.parseConf($fname): ignoring invalid autoconf: ${ymap.toMap}")
          val failedConf = SMGAutoTargetConf.configErrorTarget(ymap.toMap)
          failedRet += SMGAutoTargetStatus(failedConf, Some(s"Config Error - ${confOrStatus.right.get}"))
        }
      }
    }
    (ret.toList, failedRet.toList)
  }

  private def parseTargetsInclude(fn: String,
                                  outputFiles: mutable.Set[String],
                                  confOutputDir: Option[String]): (Seq[SMGAutoTargetConf], Seq[SMGAutoTargetStatus]) = {
    try {
      val confTxt = SMGFileUtil.getFileContents(fn)
      val yaml = new Yaml()
      val yamlList = yobjList(yaml.load(confTxt))
      parseTargetsSeq(yamlList, fn, outputFiles, confOutputDir)
    } catch { case t : Throwable =>
      log.ex(t, s"SMGAutoConfPluginConfParser.parseTargetsInclude($fn): unexpected error: ${t.getMessage}")
      (Seq(), Seq())
    }
  }

  private def parseConf(): SMGAutoConfPluginConf = {
    val confTxt = SMGFileUtil.getFileContents(confFile)
    val yaml = new Yaml()
    val yamlTopObject: Object = yaml.load(confTxt)
    val pluginConfObj = yobjMap(yamlTopObject)(pluginId)
    if (pluginConfObj == null)
      return SMGAutoConfPluginConf.empty
    val pluginConfMap = yobjMap(pluginConfObj)
    val templateDirs = pluginConfMap.get("template_dirs").map { o =>
      yobjList(o).map(_.toString) ++ BUILTIN_TEMPLATE_DIRS
    }.getOrElse(BUILTIN_TEMPLATE_DIRS)
    val confOutputDir = pluginConfMap.get("conf_output_dir").map(_.toString)
    val targetConfs = if (pluginConfMap.contains("targets")){
      val yamlList = yobjList(pluginConfMap("targets"))
      parseTargetsSeq(yamlList, confFile, mutable.Set[String](), confOutputDir)
    } else (Seq(), Seq())
    val confOutputDirOwned = pluginConfMap.get("conf_output_dir_owned").exists(_.toString == "true")
    if (confOutputDir.isDefined && confOutputDirOwned){
      val dirFile = new File(confOutputDir.get)
      if (!dirFile.exists())
        dirFile.mkdirs() // this would throw if there is fs/permissions issue and reject the conf
    }
    val preventTemplateReload = pluginConfMap.get("prevent_template_reload").exists(_.toString == "true")
    SMGAutoConfPluginConf(
      targets = targetConfs._1,
      failedTargets = targetConfs._2,
      templateDirs = templateDirs,
      confOutputDir = confOutputDir,
      confOutputDirOwned = confOutputDirOwned,
      preventTemplateReload = preventTemplateReload
    )
  }

  private var myConf: SMGAutoConfPluginConf = try {
    parseConf()
  } catch { case t: Throwable =>
    log.ex(t, s"SMGAutoConfPluginConfParser.init - unexpected exception (assuming empty conf): ${t.getMessage}")
    SMGAutoConfPluginConf.empty
  }

  def reload(): Unit = {
    try {
      myConf = parseConf()
    } catch { case t: Throwable =>
      log.ex(t, s"SMGAutoConfPluginConfParser.reload - unexpected exception (config NOT reloaded): ${t.getMessage}")
      log.ex(t, s"SMGAutoConfPluginConfParser.reload - unexpected exception (config NOT reloaded): ${t.getMessage}")
    }
  }

  def conf: SMGAutoConfPluginConf = myConf
}
