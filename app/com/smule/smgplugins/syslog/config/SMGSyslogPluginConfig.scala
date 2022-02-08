package com.smule.smgplugins.syslog.config

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.{SMGFileUtil, SMGLoggerApi}
import org.yaml.snakeyaml.Yaml

import java.io.File
import scala.collection.mutable

case class SMGSyslogPluginConfig(
                                  val maxCacheCapacity: Int,
                                  pipelineConfigs: List[PipelineConfig]
                                ) {
  def validate(): SMGSyslogPluginConfig = {
    val serverIds = mutable.Set[String]()
    val hostPorts = mutable.Set[String]()
    val baseDirs = mutable.Set[String]()
    pipelineConfigs.foreach { pc =>
      if (serverIds.contains(pc.serverId)){
        throw new RuntimeException(s"Duplicate serverIds: ${pc.serverId}")
      }
      serverIds += pc.serverId
      val hostPort = pc.syslogServerConfig.bindHost + ":" + pc.syslogServerConfig.bindPort.toString
      if (hostPorts.contains(hostPort)){
        throw new RuntimeException(s"Duplicate host:port (will fail to bind) ${pc.serverId}: ${hostPort}")
      }
      hostPorts += hostPort
      if (baseDirs.contains(pc.smgObjectTemplate.rrdBaseDir)){
        throw new RuntimeException(s"Duplicate rrdBaseDir (this is not supported) ${pc.serverId}: ${pc.smgObjectTemplate.rrdBaseDir}")
      }
      baseDirs += pc.smgObjectTemplate.rrdBaseDir
      new File(pc.smgObjectTemplate.rrdBaseDir).mkdirs()
    }
    this
  }
}

object SMGSyslogPluginConfig {

  private def loadYamlListFile(fname: String, log: SMGLoggerApi): Seq[Object] = {
    try {
      val confTxt = SMGFileUtil.getFileContents(fname)
      val yaml = new Yaml()
      yobjList(yaml.load(confTxt)).toSeq
    } catch { case t : Throwable =>
      log.ex(t, s"SMGSyslogPluginConfig.parsePluginConfFile($fname): unexpected error: ${t.getMessage}")
      Seq()
    }
  }

  private def parsePipelinesSeq(yamlList: Seq[Object], fname: String, pluginInterval: Int, log: SMGLoggerApi): Seq[PipelineConfig] = {
    yamlList.flatMap { o =>
      try {
        val mm = yobjMap(o)
        if (!mm.contains("include")) {
          val ppc = PipelineConfig.fromYmap(mm, pluginInterval, log)
          Seq(ppc).flatten
        } else {
          val includeGlob = mm("include").toString
          val includeFiles = SMGConfigParser.expandGlob(includeGlob, log)
          includeFiles.flatMap { fn =>
            val lst = loadYamlListFile(fn, log)
            parsePipelinesSeq(lst, fn, pluginInterval, log)
          }
        }
      } catch { case t: Throwable =>
        log.ex(t, s"SMGSyslogPluginConfig.parsePipelinesSeq($fname): unexpected error: ${t.getMessage}")
        Seq()
      }
    }
  }

  def parsePluginConfFile(pluginId: String, confFile: String, pluginInterval: Int, log: SMGLoggerApi): SMGSyslogPluginConfig = {
    var maxCacheCapacity: Int = 1000000
    // TODO
    val ret = try {
      val confTxt = SMGFileUtil.getFileContents(confFile)
      val yaml = new Yaml()
      val topLevel = yobjMap(yobjMap(yaml.load(confTxt))(pluginId))
      if (topLevel.contains("max_cache_capacity")){
        maxCacheCapacity = topLevel("max_cache_capacity").toString.toInt
      }
      val yamlList = if (topLevel.contains("servers"))
        yobjList(topLevel("servers"))
      else
        Seq()
      parsePipelinesSeq(yamlList, confTxt, pluginInterval, log)
    } catch { case t : Throwable =>
      log.ex(t, s"SMGSyslogPluginConfig.parsePluginConfFile($confFile): unexpected error: ${t.getMessage}")
      Seq()
    }
    val confRet = SMGSyslogPluginConfig(
      maxCacheCapacity,
      ret.toList
    )
    confRet.validate() // will throw on issues
  }

}