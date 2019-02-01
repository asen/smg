package com.smule.smgplugins.spiker

import java.io.{BufferedWriter, File, FileWriter}
import java.text.SimpleDateFormat
import java.util
import java.util.Date

import com.smule.smg._
import com.smule.smg.config.{SMGConfIndex, SMGConfigService}
import com.smule.smg.core.SMGObjectView
import com.smule.smg.remote._
import com.smule.smg.grapher.SMGAggObjectView
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smg.rrd.{SMGRrdFetch, SMGRrdFetchAgg, SMGRrdFetchParams}
import org.yaml.snakeyaml.Yaml
import play.libs.Akka

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer


/**
  * This plugin is deprecated and no longer enabled by default.
  * Kept here as (possibly bad and/or outdated) plugin code example.
  */
class SMGSpikerPlugin(val pluginId: String,
                      val interval: Int,
                      val pluginConfFile: String,
                      val smgConfSvc: SMGConfigService
                     ) extends SMGPlugin {


  private val myEc: ExecutionContext = Akka.system.dispatchers.lookup("akka-contexts.spiker-plugin")

  override def objects: Seq[SMGObjectView] = List()
  override def indexes: Seq[SMGConfIndex] = List()

  private val log = new SMGPluginLogger(pluginId)


  private val MAX_ISSUES_TO_REPORT_IN_STATUS_FILE = 10

  private var confMap = Map[String,Object]()

  private var ignoreRxs = List[String]()
  private var alertRxs = List[String]()
  private var ignoreLabelsRxs = List[String]()

  private var currentIssues = List[SMGSpikerAnomaly]()
  private var lastRunObjectsChecked = 0
  private var lastRunFinished : Option[Date] = None

  private def getRxs(key: String): List[String] = {
    confMap.getOrElse(key, new util.ArrayList[String]()).
      asInstanceOf[java.util.ArrayList[String]].toList
  }

  private def getMatchConfigs(defaultParams: SpikeCheckParams): List[SpikeCheckConfig] = {
    confMap.getOrElse("match", new util.ArrayList[Object]()).
      asInstanceOf[java.util.ArrayList[Object]].map {
      case rx: String =>
        SpikeCheckConfig(rx, confResolution, confDataPeriod, defaultParams)
      case o =>
        val om = o.asInstanceOf[util.Map[String, Object]]
        val myParams = SpikeCheckParams(
          om.getOrDefault("spike_k", defaultParams.spikeK.asInstanceOf[Object]).asInstanceOf[Double],
          om.getOrDefault("min_spike_stddev", defaultParams.minStddev.asInstanceOf[Object]).asInstanceOf[Double],
          om.getOrDefault("sample_size", defaultParams.sampleSize.asInstanceOf[Object]).asInstanceOf[Int],
          if (om.containsKey("reg_offset"))
            Some(om.get("reg_offset").asInstanceOf[Int])
          else defaultParams.regOffset,
          if (om.containsKey("reg_sample_size"))
            Some(om.get("reg_sample_size").asInstanceOf[Int])
          else defaultParams.regSampleSize,
          om.getOrDefault("min_data_points", defaultParams.minDataPoints.asInstanceOf[Object]).asInstanceOf[Int]
        )
        SpikeCheckConfig(
          om.get("rx").asInstanceOf[String],
          om.getOrDefault("resolution", confResolution.asInstanceOf[Object]).asInstanceOf[Int],
          om.getOrDefault("data_period", confDataPeriod).toString,
          myParams
        )
    }.toList
  }


  private def confMinAlertIssues: Int = confMap.getOrElse("min_alert_issues", 2).asInstanceOf[Int]

  private def confStatusFile: Option[String] = confMap.get("status_file").map(_.toString)
  private def confUrlHostPrefix: Option[String] = confMap.get("host_prefix").map(_.toString)

  private def confHistoryBaseDir: Option[String] = confMap.get("history_basedir").map(_.toString)
  private def confHistoryBaseUrl: Option[String] = confMap.get("history_baseurl").map(_.toString)

  private def confRegSampleSize: Option[Int] = confMap.get("reg_sample_size").map(_.asInstanceOf[Int])
  private def confRegOffset: Option[Int] = confMap.get("reg_offset").map(_.asInstanceOf[Int])

  private def confSpikeK: Double = confMap.getOrElse("spike_k", 1.5).asInstanceOf[Double]
  private def confSampleSize: Int = confMap.getOrElse("sample_size", 12).asInstanceOf[Int]
  private def confMinDataPoints: Int = confMap.getOrElse("min_data_points", confSampleSize * 10).asInstanceOf[Int]
  private def confResolution: Int = confMap.getOrElse("resolution", 300).asInstanceOf[Int]
  private def confMinStddev: Double = confMap.getOrElse("min_spike_stddev", 0.1).asInstanceOf[Double]
  private def confDataPeriod: String = confMap.getOrElse("data_period", "24h").toString

  override def reloadConf(): Unit = {
    try {
      val confTxt = Source.fromFile(pluginConfFile).mkString
      val yaml = new Yaml();
      val newMap = yaml.load(confTxt).asInstanceOf[java.util.Map[String, Object]]
      confMap.synchronized {
        confMap = newMap(pluginId).asInstanceOf[java.util.Map[String, Object]].toMap
      }
      ignoreRxs.synchronized {
        ignoreRxs = getRxs("ignore")
      }
      alertRxs.synchronized {
        alertRxs = getRxs("alert")
      }
      ignoreLabelsRxs.synchronized {
        ignoreLabelsRxs = getRxs("ignore_labels")
      }
      log.debug("SMGSpikerPlugin.reloadConf: confMap=" + confMap)
    } catch {
      case t: Throwable => log.ex(t, "SMGSpikerPlugin.reloadConf: Unexpected exception: " + t)
    }
  }

  private def getMatchingObjects(matchConf: SpikeCheckConfig): Seq[SMGObjectView] = {
    smgConfSvc.config.viewObjects.filter { obj =>
      SMGRemote.isLocalObj(obj.id) &&
        obj.id.matches(matchConf.rx) &&
        (!ignoreRxs.exists(rx => obj.id.matches(rx)))
    }.toList
  }

  private def fetchData(obj: SMGObjectView, vix: Int, fps: SMGRrdFetchParams): List[Double] = {
    val rows = if (obj.isAgg) {
      new SMGRrdFetchAgg(smgConfSvc.config.rrdConf, obj.asInstanceOf[SMGAggObjectView]).fetch(fps)
    } else {
      if (obj.rrdFile.isEmpty){
        log.error("SMGSpikerPlugin.fetchData: Attempt to fetch from non-existing rrd file: " + obj)
        return List()
      }
      new SMGRrdFetch(smgConfSvc.config.rrdConf, obj).fetch(fps)
    }
    rows.filter(_.vals.nonEmpty).map { r =>
      r.vals(vix)
    }
  }

  private def isAlert(obj:SMGObjectView): Boolean ={
    alertRxs.exists(rx => obj.id.matches(rx))
  }

  private def myRun(): Unit = {
    var newIssues = ListBuffer[SMGSpikerAnomaly]()
    val defaultParams = SpikeCheckParams(
      confSpikeK,
      confMinStddev,
      confSampleSize,
      confRegOffset,
      confRegSampleSize,
      confMinDataPoints)

    val confs = getMatchConfigs(defaultParams)
    var checkedCount = 0
    confs.foreach { sc =>
      val toCheck = getMatchingObjects(sc)
      log.debug("SMGSpikerPlugin.run: spikeConf=" + sc + " toCheck.size=" + toCheck.size)
      val fps = SMGRrdFetchParams(Some(sc.resolution), Some(sc.dataPeriod), None, filterNan = true)
      toCheck.foreach { obj =>
        checkedCount += 1
        for ((v, vix) <- obj.filteredVars(false).zipWithIndex ; if !ignoreLabelsRxs.exists(rx => v("label").matches(rx))) {
          val data = fetchData(obj, vix, fps)
          val dataSize = data.size
          if (data.size <= sc.params.minDataPoints) {
            log.debug("SMGSpikerPlugin.run: could not fetch enough data (data.size=" + dataSize + ") for " + obj.id)
          } else {
            val checkSpikeRet = SpikeDetector.checkSpike(sc.params, data)
            if (checkSpikeRet.isDefined){
              val atype = if (checkSpikeRet.get.matches(".*DROP:.*")) "drop" else "spike" // XXX ugly at best
              val na = SMGSpikerAnomaly(obj, vix, atype, checkSpikeRet.get, isAlert(obj))
              log.debug("SMGSpikerPlugin.run: detected anomaly: " + na.toTxt() + " : " + checkSpikeRet.get)
              newIssues += na
            }
          }
        }
      }
    }

    currentIssues.synchronized{
      currentIssues = newIssues.toList
    }
    lastRunObjectsChecked = checkedCount
    lastRunFinished = Some(new Date())
    updateStatusFile(lastRunObjectsChecked, currentIssues.filter(_.alert))
    updateHistory()
    log.info("SMGSpikerPlugin.run: Done")
  }

  override def run(): Unit = {
    Future {
      try {
        if (!checkAndSetRunning) {
          log.error("SMGSpikerPlugin.run: " + this.pluginId + " overlapping runs - aborting")
        } else {
          myRun()
        }
      } catch {
        case t: Throwable => log.ex(t, "SMGSpikerPlugin.run: Unexpected exception: " + t)
      } finally {
        finished()
      }
    }(myEc)
  }

  private def commonHtmlContent : String = {
    val iss = currentIssues
    val lastRunTimeStr = if (lastRunFinished.isDefined) "(finished at " + lastRunFinished.get + ")" else ""
    val xml = if (iss.isEmpty) {
      <h3>No current issues detected - {lastRunObjectsChecked} objects checked last run</h3>
      <p>{lastRunTimeStr}</p>
    } else {
      <h3>Some issues detected: {iss.size} issues in {lastRunObjectsChecked} objects checked last run</h3>
      <p>{lastRunTimeStr}</p>
        <ul>{
          iss.map { is =>
            <li>{scala.xml.Unparsed(is.toHtml)}</li>
          }
          }</ul>
    }
    xml.mkString("\n")
  }

  override def htmlContent(httpParams: Map[String,String]): String =
    s"""
<h1>Spiker report</h1>
<p><a href="${confHistoryBaseUrl.getOrElse("history_baseurl_IS_UNDEFINED")}/$todayHtmlStatusFile">Today's history</a></p>
    """ + commonHtmlContent

  private def updateStatusFile(objsSize:Int, iss: Seq[SMGSpikerAnomaly]): Unit = {
    val ofn = confStatusFile
    if (ofn.isEmpty) {
      log.error("SMGSpikerPlugin.updateStatusFile: no status file configured")
      return
    }
    val statusText = if (iss.size < confMinAlertIssues) {
      s"OK: Less than $confMinAlertIssues issues detected ($objsSize graphs checked, ${iss.size} issues): ${confUrlHostPrefix.getOrElse("")}/plugin/spiker"
    } else {
      s"WARNING: Some issues detected ($objsSize checked, ${iss.size} issues): ${confUrlHostPrefix.getOrElse("")}/plugin/spiker Examples: " +
        iss.take(MAX_ISSUES_TO_REPORT_IN_STATUS_FILE).map(_.toTxt(confUrlHostPrefix)).mkString(", ")
    }
    writeTextFile(new File(ofn.get), statusText + "\n")
  }

  private val myDateFmt = new SimpleDateFormat("yyyy-MM-dd")

  private def todayHtmlStatusFile = myDateFmt.format(new Date()) + ".html"

  private def updateHistory(): Unit = {
    val historyBaseDir = confHistoryBaseDir
    val historyBaseUrl = confHistoryBaseUrl
    if (historyBaseDir.isEmpty || historyBaseUrl.isEmpty) {
      log.warn("SMGSpikerPlugin.updateStatusFile: no history dirs configured")
      return
    }
    val baseDir = historyBaseDir.get
    val baseUrl = historyBaseUrl.get
    val todaysFile = new File(baseDir + "/" + todayHtmlStatusFile)
    val oldData = if (todaysFile.exists()) Source.fromFile(todaysFile).mkString else ""
    val newData = "<h2>Report generatated at " + new Date().toString + "</h2>\n" + commonHtmlContent + "\n<hr/>\n" + oldData
    writeTextFile(todaysFile, newData)
  }

  private def writeTextFile(file: File, txt: String): Unit = {
    // FileWriter
    val bw = new BufferedWriter(new FileWriter(file))
    try {
      bw.write(txt)
    } finally {
      bw.close()
    }
  }


}
