package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGCmd, SMGFileUtil, SMGLoggerApi}

import java.io.File
import java.net.InetAddress
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.util.Try

class SMGAutoTargetProcessor(
                              pluginConf: SMGAutoConfPluginConf,
                              templateProcessor: SMGTemplateProcessor,
                              smgConfSvc: SMGConfigService,
                              log: SMGLoggerApi
                            ) {

  def expandCommandStr(orig: String, ctxMap: mutable.Map[String,Object]): String = {
    var ret = orig
    ctxMap.keys.foreach { k =>
      if (k != "command") // avoid expanding %command%
        ret = ret.replace(s"%${k}%", ctxMap(k).toString)
    }
    ret
  }

  def getTargetContext(conf: SMGAutoTargetConf): Option[Map[String, Object]] = {
    val dynMap = mutable.Map[String,Object]()
    dynMap ++= conf.staticMap
    if (conf.resolveName){
      // we know that conf.nodeName.isDefined
      val nodeHost = Try(InetAddress.getByName(conf.nodeName.get).getHostAddress).toOption
      if (nodeHost.isDefined)
        dynMap.put("node_host", nodeHost.get)
      else
        log.warn(s"SMGAutoTargetProcessor: Unable to resolve " +
          s"node_host from node_name: ${conf.nodeName.get}")
    }
    val expandedCommandOpt = conf.command.map(c => expandCommandStr(c, dynMap))
    if (expandedCommandOpt.isDefined)
      dynMap.put("command", expandedCommandOpt.get)
    if (conf.runtimeData) {
      val cmd = SMGCmd(expandedCommandOpt.get, conf.runtimeDataTimeoutSec.getOrElse(30))
      val data: Option[Object] = try {
        Some(smgConfSvc.runFetchCommand(cmd, None).data)
      } catch {
        case t: Throwable =>
          log.warn(s"SMGAutoTargetProcessor: Unable to retrieve " +
            s"data from command: ${conf.command.get}, target will be skipped: ${t.getMessage}")
          None
      }
      if (data.isDefined)
        dynMap.put("data", data.get)
      else
        return None
    }
    Some(dynMap.toMap)
  }

  def processTarget(conf: SMGAutoTargetConf): Boolean = {
    val confOutputFile = conf.confOutputFile(pluginConf.confOutputDir)
    try {
      val ctxOpt = getTargetContext(conf)
      if (ctxOpt.isEmpty)
        return false
      val templateFile = pluginConf.getTemplateFilename(conf.template)
      val outputContentsOpt = templateProcessor.processTemplate(templateFile, ctxOpt.get)
      if (outputContentsOpt.isEmpty) {
        return false
      }
      val outputContents = outputContentsOpt.get
      val oldContents = if (Files.exists(Paths.get(confOutputFile)))
        SMGFileUtil.getFileContents(confOutputFile)
      else
        ""
      if (oldContents == outputContents) {
        log.debug(s"SMGAutoTargetProcessor.processTarget(${confOutputFile}) - no config changes detected")
        return false
      }
      SMGFileUtil.outputStringToFile(confOutputFile, outputContents, None)
      true
    } catch { case t: Throwable =>
      log.ex(t, s"SMGAutoTargetProcessor.processTarget(${confOutputFile}): " +
        s"unexpected error: ${t.getMessage}")
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
          log.info(s"SMGAutoTargetProcessor.cleanupOwnedDir: deleted file: $fullFn")
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
      log.debug(s"SMGAutoTargetProcessor: Processing target conf: ${targetConf.uid}: conf=$targetConf")
      val targetReload = processTarget(targetConf)
      if (targetReload) {
        log.info(s"SMGAutoTargetProcessor: Done processing target conf: ${targetConf.uid}: " +
          s"targetReload=$targetReload needReload=$needReload")
        needReload = true
      }
    }
    if (pluginConf.confOutputDir.isDefined && pluginConf.confOutputDirOwned){
      if (cleanupOwnedDir(pluginConf.confOutputDir.get,
        pluginConf.targets.map(_.confOutputFile(pluginConf.confOutputDir))))
        needReload = true
    }
    needReload
  }
}
