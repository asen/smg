package com.smule.smgplugins.scrape

import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core._


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
    val parsed = OpenMetricsStat.parseText(res.asStr, log)
    val ogen = new SMGScrapeObjectGen(tgt, parsed, log)
    val objs = ogen.generateSMGObjects()
    val cgen = new SMGYamlConfigGen()
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

  // throw on i/o error
  private def outputStringToFile(fname: String, inp: String, backupExt: Option[String]): Unit = {
    val filePath = Paths.get(fname)
    val backupFilePath = backupExt.map { ext =>
      Paths.get(fname.split('.').dropRight(1).mkString(".") + "." + ext)
    }
    val dir = filePath.getParent
    val tempFn = Files.createTempFile(dir, s".tmp-${filePath.getFileName}", "-tmp")
    try {
      if (backupFilePath.isDefined && Files.exists(filePath))
        Files.copy(filePath, backupFilePath.get, StandardCopyOption.REPLACE_EXISTING)
      Files.writeString(tempFn, inp)
      Files.move(tempFn, filePath, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(tempFn)
    }
  }

  // return true if output conf file has changed and needs reload
  def processTarget(tgt: SMGScrapeTargetConf): Boolean = {
    try{
      val resOpt = runTargetCommand(tgt)
      if (resOpt.isEmpty)
        return false
      val yamlText = getYamlText(tgt, resOpt.get)
      val oldYamlText = if (new File(tgt.confOutput).exists())
        SMGFileUtil.getFileContents(tgt.confOutput)
      else
        ""
      if (oldYamlText == yamlText) {
        log.debug(s"SMGScrapeTargetProcessor.processTarget(${tgt.uid}) - no config changes detected")
        return false
      }
      outputStringToFile(tgt.confOutput, yamlText, tgt.confOutputBackupExt)
      true
    } catch { case t: Throwable =>
      log.ex(t, s"SMGScrapeTargetProcessor.processTarget(${tgt.uid}): unexpected error: ${t.getMessage}")
      false
    }
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
    needReload
  }
}
