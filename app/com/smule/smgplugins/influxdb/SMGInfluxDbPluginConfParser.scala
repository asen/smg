package com.smule.smgplugins.influxdb

import com.smule.smg.config.SMGConfigParser.yobjMap
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGFileUtil, SMGLoggerApi}
import org.yaml.snakeyaml.Yaml

import scala.collection.mutable

class SMGInfluxDbPluginConfParser(pluginId: String, confFile: String, log: SMGLoggerApi,
                                  smgConfSvc: SMGConfigService) {

  private val SMG_GLOBALS_INFLUXDB_PREFIX = "$influxdb_"

  private def parseConf(smgGlobals: Option[Map[String, String]]): SMGInfluxDbPluginConf = {
    val confTxt = SMGFileUtil.getFileContents(confFile)
    val yaml = new Yaml()
    val yamlTopObject: Object = yaml.load(confTxt)
    val pluginConfObj = yobjMap(yamlTopObject)(pluginId)
    val ymap = if (pluginConfObj == null)
      Map[String, Object]()
    else
      yobjMap(pluginConfObj)
    val resultConfMap = mutable.Map[String, String]()
    ymap.foreach { case (k,v) =>
      resultConfMap.put(k, v.toString)
    }
    if (smgGlobals.isDefined){
      smgGlobals.get.withFilter { case (k,v) =>
        k.startsWith(SMG_GLOBALS_INFLUXDB_PREFIX)
      }.foreach { case (k,v) =>
        resultConfMap.put(k.stripPrefix(SMG_GLOBALS_INFLUXDB_PREFIX),v)
      }
    }
    SMGInfluxDbPluginConf.fromMap(resultConfMap.toMap)
  }

  private var myConf: SMGInfluxDbPluginConf = try {
    parseConf(None)
  } catch { case t: Throwable =>
    log.ex(t, s"SMGKubePluginConfParser.init - unexpected exception (assuming empty conf): ${t.getMessage}")
    SMGInfluxDbPluginConf.empty
  }

  def reload(): Unit = {
    try {
      myConf = parseConf(Some(smgConfSvc.config.globals))
    } catch { case t: Throwable =>
      log.ex(t, s"SMGKubePluginConfParser.reload - unexpected exception (config NOT reloaded): ${t.getMessage}")
    }
  }

  def conf: SMGInfluxDbPluginConf = myConf
}
