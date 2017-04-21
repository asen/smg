package com.smule.smgplugins.jmx

import java.io.File
import java.nio.file.{FileSystems, PathMatcher}
import java.util

import com.smule.smg._
import org.yaml.snakeyaml.Yaml

import scala.collection.mutable.ListBuffer
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.io.Source

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

  private def getListOfFiles(dir: String, matcher: PathMatcher): List[File] = {
    val d = new File(dir)
    if (d.exists && d.isDirectory) {
      d.listFiles.filter { (f: File) =>
        //        log.info(f.toPath);
        f.isFile && matcher.matches(f.toPath)
      }.toList.sortBy(f => f.toPath)
    } else {
      log.warn("SMGJmxConfigParser.getListOfFiles: CONFIG_WARNING: " + dir + " : glob did not match anything")
      List[File]()
    }
  }

  private def expandGlob(glob: String): List[String] = {
    if (new File(glob).isFile) {
      return List(glob)
    }
    //    log.info("expandGlob: Expanding glob: " + glob)
    val fs = FileSystems.getDefault
    var dir = glob
    val sepIdx = glob.lastIndexOf(fs.getSeparator)
    if (sepIdx != -1) {
      dir = glob.substring(0, sepIdx)
    }
    //    log.info("expandGlob: listing dir " + dir + " with glob " + glob)
    val matcher = fs.getPathMatcher("glob:" + glob)
    getListOfFiles(dir, matcher).map((f: File) => f.getPath)
  }

  private def keyValFromMap(m: java.util.Map[String, Object]): (String, Object) = {
    val firstKey = m.keys.collectFirst[String] { case x => x }.getOrElse("")
    val retVal = m.remove(firstKey)
    if (retVal != null)
      (firstKey, retVal)
    else
      (firstKey, m)
  }

  private def validateOid(oid: String): Boolean = oid.matches("^[\\w\\._-]+$")

  private def processObject(rrdDir: String, interval: Int, pfId: String, hostPort: String, baseId: String,
                            oid: String, ymap: java.util.Map[String, Object], confFile: String ): Option[SMGJmxObject] = {
    if (!validateOid(oid)){
      log.error("SMGJmxConfigParser.processObject(" + confFile + "): CONFIG_ERROR: Skipping object with invalid id: " + oid)
      None
    } else {
      try {
        val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid,
          ymap.toMap.map(kv => (kv._1, kv._2.toString)))
        // XXX support for both rrdType (deprecated) and rrd_type syntax
        val myRrdType = if (ymap.contains("rrd_type"))
          ymap("rrd_type").toString
        else ymap.getOrElse("rrdType", "GAUGE").toString

        val obj = SMGJmxObject(baseId,
          oid,
          pfId,
          ymap.getOrElse("title", oid).toString,
          hostPort,
          ymap("jmxName").toString,
          myRrdType,
          ymap("vars").asInstanceOf[util.ArrayList[util.Map[String, Object]]].toList.map(
            (m: util.Map[String,Object]) => m.map { t => (t._1, t._2.toString) }.toMap
          ),
          rrdDir,
          interval,
          Some(pluginId),
          notifyConf
        )
        Some(obj)
      } catch {
        case x : Throwable => log.ex(x, "SMGJmxConfigParser.processObject(" + confFile +
          "): CONFIG_ERROR")
          None
      }
    }
  }

  def hostPortPfId(hostPort: String) = s"$pluginId.${hostPort.replace(':', '.')}"

  def parseHostDef(rrdDir: String, interval: Int, baseId: String, ymap: java.util.Map[String, Object],
                   confFile: String): (List[SMGJmxObject], SMGPreFetchCmd) = {
    val hostPort = ymap("hostPort").asInstanceOf[String]
    val pfId = hostPortPfId(hostPort)
    val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, pfId,
      ymap.toMap.map(kv => (kv._1, kv._2.toString)))
    val pf = SMGPreFetchCmd(pfId, SMGCmd(s"jmx://$hostPort"), None, ignoreTs = true, childConc = 1, notifyConf)
    var ret = ListBuffer[SMGJmxObject]()
    ymap("objs").asInstanceOf[java.util.ArrayList[java.util.Map[String, Object]]].foreach { oymap =>
      val t = keyValFromMap(oymap)
      val opt = processObject(rrdDir, interval, pf.id, hostPort, baseId, baseId + "." + t._1,
        t._2.asInstanceOf[java.util.Map[String, Object]], confFile)
      if (opt.isDefined) ret += opt.get
    }
    (ret.toList, pf)
  }

  def parseObjects(interval: Int, tlConf: Map[String, Object]): (List[SMGJmxObject], Map[String,SMGPreFetchCmd]) = {
    val rrdDir = tlConf("rrd_dir").asInstanceOf[String]
    val ret = ListBuffer[SMGJmxObject]()
    val retPfs = mutable.Map[String, SMGPreFetchCmd]()
    tlConf("includes").asInstanceOf[util.ArrayList[String]].foreach { globStr: String =>
      expandGlob(globStr).foreach { yamlfn =>
        val confTxt = Source.fromFile(yamlfn).mkString
        val yaml = new Yaml();
        val yamlTopObject = yaml.load(confTxt)
        yamlTopObject.asInstanceOf[java.util.List[Object]].foreach { yamlObj: Object =>
          val t = keyValFromMap(yamlObj.asInstanceOf[java.util.Map[String, Object]])
          val (lst, pf) = parseHostDef(rrdDir, interval, t._1, t._2.asInstanceOf[java.util.Map[String, Object]], yamlfn)
          ret ++= lst
          retPfs(pf.id) = pf
        }
      }
    }
    (ret.toList, retPfs.toMap)
  }

  def buildIndexes(jmxObjects: List[SMGJmxObject]): List[SMGConfIndex] = {
    val ret = ListBuffer[SMGConfIndex]()
    jmxObjects.groupBy(_.baseId).keys.foreach { baseId =>
      ret += SMGConfIndex(baseId, baseId,
        SMGFilter.fromPrefixLocal(baseId),
        None, None, None, xAgg = false, None, None, Some(pluginId), disableHeatmap = false)
    }
    val secondLevel = SMGConfIndex(pluginId+"_all", s"JMX Graphs ($pluginId - All)",
      SMGFilter.matchLocal,
      None, Some(0), None, xAgg = false, None, None, Some(pluginId), ret.map(_.id), disableHeatmap = false)
    ret += secondLevel
    val topLevel = SMGConfIndex(pluginId, s"JMX Graphs ($pluginId)",
      SMGFilter.matchLocal,
      None, Some(0), None, xAgg = false, None, None, None, Seq(pluginId+"_all"), disableHeatmap = false)
    ret += topLevel
    ret.toList
  }
}
