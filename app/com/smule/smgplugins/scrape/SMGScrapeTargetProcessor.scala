package com.smule.smgplugins.scrape

import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core._
import com.smule.smg.openmetrics.OpenMetricsStat


class SMGScrapeTargetProcessor(pluginConf: SMGScrapePluginConf,
                               smgConfSvc: SMGConfigService,
                               log: SMGLoggerApi
                              ) {


  private def runTargetCommand(tgt: SMGScrapeTargetConf): Option[CommandResult] = {
    try {
      // TODO support plugin commands (via conf svc?)
      // at least :scrape fetch should be supported internally
      val stdout = SMGCmd(tgt.command, tgt.timeoutSec).run()
      Some(CommandResultListString(stdout))
    } catch { case fetchEx: SMGFetchException =>
      log.error(s"SCRAPE_ERROR: ${tgt.uid}: ${fetchEx.getMessage}")
      None
    }
  }

  private def getYamlText(tgt: SMGScrapeTargetConf, res: CommandResult): String = {
    val parsed = OpenMetricsStat.parseText(res.asStr, log, tgt.labelsInUids)
    val ogen = new SMGScrapeObjectGen(tgt, parsed, log)
    val objs = ogen.generateSMGObjects()
    val cgen = SMGYamlConfigGen
    val out = new StringBuilder()
    out.append(s"# This file is automatically generated. Changes will be overwritten\n")
    out.append(s"# Generated by SMGScrapePlugin from scrape config ${tgt.uid}. Command: ${tgt.command}\n")

    if (objs.preFetches.nonEmpty) {
      out.append(s"\n\n### pre fetch commands\n\n")
      val pfsYamlTxt = cgen.yamlObjToStr(cgen.preFetchesToYamlList(objs.preFetches))
      out.append(pfsYamlTxt)
    } else
      out.append(s"\n\n### no pre fetch commands\n\n")

    if (objs.rrdObjects.nonEmpty) {
      out.append(s"\n\n### rrd objects\n\n")
      val objsYamlTxt = cgen.yamlObjToStr(cgen.rrdObjectsToYamlList(objs.rrdObjects))
      out.append(objsYamlTxt)
    } else
      out.append(s"\n\n### no rrd objects\n\n")

    if (objs.aggObjects.nonEmpty) {
      out.append(s"\n\n### rrd aggregate objects\n\n")
      val aggObjsYamlTxt = cgen.yamlObjToStr(cgen.rrdAggObjectsToYamlList(objs.aggObjects))
      out.append(aggObjsYamlTxt)
    } else
      out.append(s"\n\n### no rrd aggregate objects\n\n")

    if (objs.indexes.nonEmpty) {
      out.append(s"\n\n### index objects\n\n")
      val indexesYamlTxt = cgen.yamlObjToStr(cgen.confIndexesToYamlList(objs.indexes))
      out.append(indexesYamlTxt)
    } else
      out.append(s"\n\n### no index objects\n\n")

    out.append("\n\n# End of getnerated outout\n")
    out.toString()
  }

  // return true if output conf file has changed and needs reload
  def processTarget(tgt: SMGScrapeTargetConf): Boolean = {
    try{
      val resOpt = runTargetCommand(tgt)
      if (resOpt.isEmpty)
        return false
      val yamlText = getYamlText(tgt, resOpt.get)
      val confOutputFile = tgt.confOutputFile(pluginConf.confOutputDir)
      val oldYamlText = if (Files.exists(Paths.get(confOutputFile)))
        SMGFileUtil.getFileContents(confOutputFile)
      else
        ""
      if (oldYamlText == yamlText) {
        log.debug(s"SMGScrapeTargetProcessor.processTarget(${tgt.uid}) - no config changes detected")
        return false
      }
      SMGFileUtil.outputStringToFile(confOutputFile, yamlText, tgt.confOutputBackupExt)
      true
    } catch { case t: Throwable =>
      log.ex(t, s"SMGScrapeTargetProcessor.processTarget(${tgt.uid}): unexpected error: ${t.getMessage}")
      false
    }
  }

  // remove all files not part of targets
  private def cleanupOwnedDir(dir: String, ownedFiles: Seq[String]): Boolean = {
    // find the set of all files and remove the supplied ownedFile set
    val ownedFilesInDir = ownedFiles.withFilter { fn =>
      fn.startsWith(dir) && {
        val relName = fn.stripPrefix(dir).stripPrefix(File.separator)
        (new File(relName).getName == relName) //not a sub dir path
      }
    }.map { fn =>
      new File(fn).getName
    }.toSet
    val allFilesInDir = new File(dir).listFiles().withFilter(_.isFile).map(_.getName).toSet
    val toDel = allFilesInDir -- ownedFilesInDir
    // actually delete files
    if (toDel.nonEmpty){
      toDel.foreach { fn =>
        val fullFn = dir.stripSuffix(File.separator) + File.separator + fn
        try {
          Files.delete(Paths.get(fullFn))
          log.info(s"SMGScrapeTargetProcessor.cleanupOwnedDir: deleted file: $fullFn")
        } catch { case t: Throwable =>
          log.ex(t, s"Unexpected error while deleting $fullFn")
        }
      }
      true
    } else
      false
  }

  // for each target:
  //   1. run command
  //   2. parse output
  //   3. generate yaml from output
  // return true if anything changed (and conf needs reload)
  def run(): Boolean = {
    // TODO for now processing one target at a time to save the cpu for normal polling
    // may consider async processing (via an Actor in the future)
    var needReload: Boolean = false
    pluginConf.targets.foreach { targetConf =>
      log.info(s"SMGScrapeTargetProcessor: Processing taget conf: ${targetConf.uid}: conf=$targetConf")
      val targetReload = processTarget(targetConf)
      if (targetReload)
        needReload = true
      log.info(s"SMGScrapeTargetProcessor: Done processing taget conf: ${targetConf.uid}: " +
        s"targetReload=$targetReload needReload=$needReload")
    }
    if (pluginConf.confOutputDir.isDefined && pluginConf.confOutputDirOwned){
      if (cleanupOwnedDir(pluginConf.confOutputDir.get,
       pluginConf.targets.map(_.confOutputFile(pluginConf.confOutputDir))))
        needReload = true
    }
    needReload
  }
}
