package com.smule.smgplugins.mon.anom

import java.io.{File, FileWriter}

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGLoggerApi, SMGObjectUpdate}
import com.smule.smg.monitor._
import play.api.libs.json.{JsValue, Json}

import scala.collection.concurrent.TrieMap

class AnomCheck(val ckId: String, log: SMGLoggerApi, cfSvc: SMGConfigService) extends SMGMonCheck {

  private val objectVarStats = TrieMap[String, ValueMovingStats]()
  private val threshConfs = TrieMap[String, AnomThreshConf]()

  override def checkValue(ou: SMGObjectUpdate, vix: Int, ts: Int, newVal: Double, checkConf: String): SMGState = {
    val alertConf = confFromStr(checkConf)
    val movingStats = getOrCreateMovingStats(ou, vix, checkConf)

    // Update short/long term averages, to be used for spike/drop detection
    val ltMaxCounts = alertConf.maxLtCnt(ou.interval)
    val stMaxCounts = alertConf.maxStCnt(ou.interval)
    movingStats.update(ou.interval, ts, newVal, stMaxCounts, ltMaxCounts)

    def myNumFmt(d: Double) = { ou.numFmt(d, vix) }
    // check for spikes
    val alertDesc = alertConf.checkAlert(movingStats, ou.interval, stMaxCounts, ltMaxCounts, myNumFmt)
    if (alertDesc.isDefined)
      SMGState(ts, SMGState.ANOMALY, s"ANOM: ${alertDesc.get}")
    else
      SMGState(ts, SMGState.OK, s"OK: $ckId: $checkConf")
  }

  private val MAX_STATES_PER_CHUNK = 5000

  def serializeAllMonitorStates: List[String] = {
    objectVarStats.toList.grouped(MAX_STATES_PER_CHUNK).map { chunk =>
      val om = chunk.map{ t =>
        val k = t._1
        val v = t._2
        (k, v.serialize)
      }
      Json.toJson(om.toMap).toString()
    }.toList
  }

  private def monStateBaseFname(dir: String) = dir + File.separatorChar + "p-anom-states"
  private def monStateMetaFname(dir: String) = dir + File.separatorChar + "p-anom-meta.json"
  private def ixFileSuffix(ix: Int) = if (ix == 0) "" else s".$ix"

  def saveStateToDisk(dir: String): Unit = {
    try {
      log.info(s"AnomCheck.saveStateToDisk($dir) BEGIN")
      new File(dir).mkdirs()
      val statesLst = serializeAllMonitorStates
      statesLst.zipWithIndex.foreach { t =>
        val stateStr = t._1
        val ix = t._2
        val suffix = ixFileSuffix(ix)
        val monStateFname = s"${monStateBaseFname(dir)}$suffix.json"
        log.info(s"AnomCheck.saveStateToDisk $monStateFname")
        val fw = new FileWriter(monStateFname, false)
        try {
          fw.write(stateStr)
        } finally fw.close()
      }
      val metaStr = Json.toJson(Map("stateFiles" -> statesLst.size.toString)).toString()
      val fw1 = new FileWriter(monStateMetaFname(dir), false)
      try {
        fw1.write(metaStr)
      } finally fw1.close()
      log.info("AnomCheck.saveStateToDisk END")
    } catch {
      case t: Throwable => log.ex(t, "Unexpected exception in AnomCheck.saveStateToDisk")
    }
  }

  private def deserializeObjectVarStats(stateStr: String): Int = {
    var cnt = 0
    val jsm = Json.parse(stateStr).as[Map[String, JsValue]]
    jsm.foreach { t =>
      val vms = new ValueMovingStats(t._1, log)
      ValueMovingStats.deserialize(t._2, vms)
      objectVarStats.put(t._1, vms)
      cnt += 1
    }
    cnt
  }

  def loadStateFromDisk(dir: String): Unit = {
    log.info(s"AnomCheck.loadStateFromDisk($dir) BEGIN")
    try {
      val metaD: Map[String,String] = if (new File(monStateMetaFname(dir)).exists()) {
        val metaStr = cfSvc.sourceFromFile(monStateMetaFname(dir))
        Json.parse(metaStr).as[Map[String,String]]
      } else Map()
      var cnt = 0
      val numStateFiles = metaD.getOrElse("stateFiles", "1").toInt
      (0 until numStateFiles).foreach { ix =>
        val suffix = ixFileSuffix(ix)
        val monStateFname = s"${monStateBaseFname(dir)}$suffix.json"
        if (new File(monStateFname).exists()) {
          log.info(s"SMGMonitor.loadStateFromDisk $monStateFname")
          val stateStr = cfSvc.sourceFromFile(monStateFname)
          cnt += deserializeObjectVarStats(stateStr)
        }
      }
      log.info(s"AnomCheck.loadStateFromDisk END - $cnt states loaded")
    } catch {
      case x:Throwable => log.ex(x, "AnomCheck.loadStateFromDisk - unexpected error")
    }
  }

  def cleanupObsoleteStates(configSvc: SMGConfigService, pluginId: String): Unit = {
    log.info("AnomCheck.cleanupObsoleteStates BEGIN")
    val config = configSvc.config
    objectVarStats.keys.toSeq.foreach { k =>
      val (ouId, vix, conf) = ouIdVixConfFromStatsKey(k)
      val ouOpt = config.updateObjectsById.get(ouId)
      if (ouOpt.isEmpty || ouOpt.get.vars.lengthCompare(vix) <= 0) {
        log.warn(s"AnomCheck.cleanupObsoleteStates: removing state for non-existing object ouId=$ouId, vix=$vix")
        objectVarStats.remove(k)
      } else {
        val alertConfs = configSvc.objectValueAlertConfs(ouOpt.get, vix)
        if (! alertConfs.exists { ac => ac.pluginChecks.exists {pc => (pc.ckId == pluginId + "-" + ckId) && (pc.conf == conf) } }) {
          log.warn(s"AnomCheck.cleanupObsoleteStates: removing state for non-existing ckId:$ckId ouId:$ouId, vix=$vix")
          objectVarStats.remove(k)
        }
      }
    }
    threshConfs.clear()
    log.info("AnomCheck.cleanupObsoleteStates END")
  }

  private def confFromStr(confStr: String): AnomThreshConf = {
    threshConfs.getOrElseUpdate(confStr, { AnomThreshConf(confStr) })
  }

  private def objectVarStatsKey(ou: SMGObjectUpdate, vix: Int, checkConfStr: String) = {
    Seq(ou.id, vix.toString, checkConfStr).mkString(":")
  }

  private def ouIdVixConfFromStatsKey(key: String) = {
    val arr = key.split(":", 3)
    val ouId = arr(0)
    val vix = if (arr.length > 1) {
      arr(1).toInt
    } else 0
    val conf = if (arr.length > 2)
      arr(2)
    else ""
    (ouId, vix, conf)
  }

  private def getOrCreateMovingStats(ou: SMGObjectUpdate, vix: Int, checkConfStr: String): ValueMovingStats = {
    val ostatsKey = objectVarStatsKey(ou, vix, checkConfStr)
    objectVarStats.getOrElseUpdate(ostatsKey, { new ValueMovingStats(ostatsKey, log) })
  }

  override def inspectState(ou: SMGObjectUpdate, vix: Int, checkConf: String): String = {
    val ostatsKey = objectVarStatsKey(ou, vix, checkConf)
    "key=" + ostatsKey + ", val=" + objectVarStats.get(ostatsKey).map( _.serialize.toString).getOrElse("(undefined)")
  }
}
