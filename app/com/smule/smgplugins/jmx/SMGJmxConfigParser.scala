package com.smule.smgplugins.jmx

import java.io.File
import java.util

import com.smule.smg.config.{SMGConfIndex, SMGConfigParser, SMGConfigService, SMGLocalConfig}
import com.smule.smg.core.{SMGCmd, SMGFilter, SMGPreFetchCmd}
import com.smule.smg.monitor._
import com.smule.smg.plugin.SMGPluginLogger
import org.yaml.snakeyaml.Yaml

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * Config parser for the JMX plugin.
  *
  * TODO this may have to be rewritten to be more flexible. Right now it only supports
  * a single top level yaml object which in turn must include object definition yaml files.
  *
  * Check smgconf/jmx-plugin.yml and smgconf/jmx/localhost-jmx.yml for examples.
  *
  */
class SMGJmxConfigParser(val pluginId: String, val configSvc: SMGConfigService, val log: SMGPluginLogger) {

  val myCfParser = new SMGConfigParser(log)

  def getLabels(ymap: mutable.Map[String, Object]): Map[String,String] ={
    if (ymap.contains("labels") && (ymap("labels") != null)) {
      ymap("labels").asInstanceOf[java.util.Map[String, Object]].
        asScala.map { case (k, v) => (k, v.toString)}.toMap
    } else Map()
  }

  private def processObject(rrdDir: String, interval: Int, pfId: String, parentIds: Seq[String], hostPort: String,
                            baseId: String, oid: String, ymap: mutable.Map[String, Object],
                            confFile: String ): Option[SMGJmxObject] = {
    if (!myCfParser.validateOid(oid)){
      log.error("SMGJmxConfigParser.processObject(" + confFile + "): CONFIG_ERROR: Skipping object with invalid id: " + oid)
      None
    } else {
      try {
        val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid,
          ymap.toMap.map(kv => (kv._1, kv._2.toString)))
        // XXX support for both rrdType (deprecated) and rrd_type syntax
        val myRrdType = myCfParser.getRrdType(ymap, None)

        val obj = SMGJmxObject(baseId,
          oid,
          parentIds,
          pfId,
          ymap.getOrElse("title", oid).toString,
          hostPort,
          ymap("jmxName").toString,
          myRrdType,
          myCfParser.ymapVars(ymap),
          rrdDir,
          interval,
          Some(pluginId),
          notifyConf,
          getLabels(ymap)
        )
        Some(obj)
      } catch {
        case x : Throwable => log.ex(x, "SMGJmxConfigParser.processObject(" + confFile +
          "): CONFIG_ERROR")
          None
      }
    }
  }

  def parseHostDef(rrdDir: String, interval: Int, baseId: String, ymap: mutable.Map[String, Object],
                   confFile: String): (List[SMGJmxObject], SMGPreFetchCmd) = {
    val hostPort = ymap("hostPort").asInstanceOf[String]
    val pfId = baseId
    val parentState = if (ymap.contains("parentState")) { Some(ymap("parentState").toString) } else None
    val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, pfId,
      ymap.toMap.map(kv => (kv._1, kv._2.toString)))
    val pf = SMGPreFetchCmd(pfId, SMGCmd(s"jmx://$hostPort"),
                            Some(s"JMX Connection to $hostPort"), parentState, ignoreTs = true,
                            childConc = 1, notifyConf, passData = false)
    val parentIds = Seq(Some(pfId), parentState).flatten
    var ret = ListBuffer[SMGJmxObject]()
    ymap("objs").asInstanceOf[java.util.ArrayList[java.util.Map[String, Object]]].asScala.foreach { oymap =>
      val t = myCfParser.keyValFromMap(oymap)
      val opt = processObject(rrdDir, interval, pf.id, parentIds, hostPort, baseId, baseId + "." + t._1,
        t._2.asInstanceOf[java.util.Map[String, Object]].asScala, confFile)
      if (opt.isDefined) ret += opt.get
    }
    (ret.toList, pf)
  }

  def parseObjects(interval: Int, tlConf: Map[String, Object]): (List[SMGJmxObject], Map[String,SMGPreFetchCmd]) = {
    val rrdDir = if (tlConf.contains("rrd_dir"))
      tlConf("rrd_dir").asInstanceOf[String]
    else
      SMGLocalConfig.DEFAULT_RRD_DIR + "/jmx"
    new File(rrdDir).mkdirs()
    val ret = ListBuffer[SMGJmxObject]()
    val retPfs = mutable.Map[String, SMGPreFetchCmd]()
    tlConf("includes").asInstanceOf[util.ArrayList[String]].asScala.foreach { globStr: String =>
      try {
        myCfParser.expandGlob(globStr).foreach { yamlfn =>
          try {
            val confTxt = configSvc.sourceFromFile(yamlfn)
            val yaml = new Yaml()
            val yamlTopObject: Object = yaml.load(confTxt)
            yamlTopObject.asInstanceOf[java.util.List[Object]].asScala.foreach { yamlObj: Object =>
              val t = myCfParser.keyValFromMap(yamlObj.asInstanceOf[java.util.Map[String, Object]])
              val (lst, pf) = parseHostDef(rrdDir, interval, t._1, t._2.asInstanceOf[java.util.Map[String, Object]].asScala, yamlfn)
              ret ++= lst
              retPfs(pf.id) = pf
            }
          } catch {
            case t: Throwable =>
              log.error(s"Unexpected exception parsing $yamlfn")
          }
        }
      } catch {
        case t: Throwable =>
          log.error(s"Unexpected exception processing glob $globStr")
      }
    }
    (ret.toList, retPfs.toMap)
  }

  def buildIndexes(jmxObjects: List[SMGJmxObject]): List[SMGConfIndex] = {
    val ret = ListBuffer[SMGConfIndex]()
    jmxObjects.groupBy(_.baseId).keys.foreach { baseId =>
      ret += SMGConfIndex(
        id = baseId,
        title = baseId,
        flt = SMGFilter.fromPrefixLocal(baseId),
        cols = None,
        rows = None,
        aggOp = None,
        xRemoteAgg = false,
        aggGroupBy = None,
        gbParam = None,
        period = None,
        desc = None,
        parentId = Some(pluginId),
        disableHeatmap = false)
    }
    val secondLevel = SMGConfIndex(
      id = pluginId + "_all",
      title = s"JMX Graphs ($pluginId - All)",
      flt = SMGFilter.matchLocal,
      cols = None, rows = Some(0), aggOp = None, xRemoteAgg = false, aggGroupBy = None, gbParam = None,
      period = None, desc = None, parentId = Some(pluginId), childIds = ret.map(_.id), disableHeatmap = false)
    ret += secondLevel
    val topLevel = SMGConfIndex(
      id = pluginId, title = s"JMX Graphs ($pluginId)",
      flt = SMGFilter.matchLocal,
      cols = None, rows = Some(0), aggOp = None, xRemoteAgg = false, aggGroupBy = None, gbParam = None,
      period = None, desc = None, parentId = None, childIds = Seq(pluginId + "_all"), disableHeatmap = false)
    ret += topLevel
    ret.toList
  }
}
