package com.smule.smg

import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable.ListBuffer

/**
  * Created by asen on 7/7/16.
  */

case class SMGMonAlertThresh(value: Double, op: String) {

  def checkAlert(fetchedValue: Double, numFmt: (Double) => String):Option[String] = {
    op match {
      case "gte" => if (fetchedValue >= value) Some(s"${numFmt(fetchedValue)} >= ${numFmt(value)}") else None
      case "gt"  => if (fetchedValue > value) Some(s"${numFmt(fetchedValue)} > ${numFmt(value)}") else None
      case "lte" => if (fetchedValue <= value) Some(s"${numFmt(fetchedValue)} <= ${numFmt(value)}") else None
      case "lt"  => if (fetchedValue < value) Some(s"${numFmt(fetchedValue)} < ${numFmt(value)}") else None
      case "eq"  => if (fetchedValue == value) Some(s"${numFmt(fetchedValue)} == ${numFmt(value)}") else None
      case badop: String => {
        SMGLogger.warn("SMGMonAlertThresh: invalid op: " + badop)
        None
      }
    }
  }
}

object SMGMonAlertConfSource extends Enumeration {
  val OBJ, INDEX, HINDEX = Value
}

case class SMGMonVarAlertConf(src: SMGMonAlertConfSource.Value,
                              srcId: String,
                              crit: Option[SMGMonAlertThresh],
                              warn: Option[SMGMonAlertThresh],
                              pluginChecks: Seq[SMGMonCheckConf]
                             ) {
  def inspect: String = {
    s"SMGMonVarAlertConf: src=$src, srcId=$srcId, crit=$crit, warn=$warn, " +
      s"pluginChecks=[${pluginChecks.map(pc => pc.ckId + ":" + pc.conf).mkString(", ")}]"
  }

  def checkValue(ou: SMGObjectUpdate, vix: Int, ts: Int, newVal: Double): SMGState = {
    var curRet: Option[SMGState] = None
    val warnThreshVal = warn.map(_.value).getOrElse(Double.NaN)
    val critThreshVal = crit.map(_.value).getOrElse(Double.NaN)
    def numFmt(d: Double) = {
      ou.numFmt(d, vix)
    }
    val descSx = threshDesc(numFmt)
    val allPluginCheckStates = pluginChecks.map { ck => ck.check.checkValue(ou, vix, ts, newVal, ck.conf) }
    curRet = if (allPluginCheckStates.nonEmpty) {
      Some(allPluginCheckStates.maxBy(_.state))
    } else None
    if (curRet.isDefined && (curRet.get.state == SMGState.OK )){
      curRet = None
    }
    if ((curRet.isEmpty || (curRet.get.state < SMGState.CRITICAL)) && crit.isDefined) {
      val alertDesc = crit.get.checkAlert(newVal, numFmt)
      if (alertDesc.isDefined)
        curRet = Some(SMGState(ts, SMGState.CRITICAL, s"CRIT: ${alertDesc.get} : $descSx"))
    }
    if ((curRet.isEmpty || (curRet.get.state < SMGState.WARNING)) && warn.isDefined ) {
      val alertDesc = warn.get.checkAlert(newVal, numFmt)
      if (alertDesc.isDefined)
        curRet = Some(SMGState(ts,SMGState.WARNING, s"WARN: ${alertDesc.get} : $descSx"))
    }
    if (curRet.isEmpty) {
      curRet = Some(SMGState(ts,SMGState.OK, s"OK: value=${numFmt(newVal)} : $descSx"))
    }
    curRet.get
  }

  def threshDesc(numFmt: (Double) => String): String = {
    val lb = ListBuffer[String]()
    if (warn.isDefined) lb += s"warn-${warn.get.op}: ${numFmt(warn.get.value)}"
    if (crit.isDefined) lb += s"crit-${crit.get.op}: ${numFmt(crit.get.value)}"
    pluginChecks.foreach { pc =>
      lb += s"p-${pc.ckId}: ${pc.conf}"
    }
    lb.mkString(", ")
  }
}

object SMGMonVarAlertConf {

  private val log = SMGLogger

  private val BUILTIN_ALERT_KEYS = Set(
    "alert-warn",
    "alert-warn-gte",
    "alert-warn-gt",
    "alert-warn-lte",
    "alert-warn-lt",
    "alert-warn-eq",
    "alert-crit",
    "alert-crit-gte",
    "alert-crit-gt",
    "alert-crit-lte",
    "alert-crit-lt",
    "alert-crit-eq"
  )

  private val PLUGIN_ALERT_KEY_PX = "alert-p-"

  private def getOp(alerKey:String) = {
    val ret = alerKey.split("-")
    if (ret.length < 3) "gte" else ret(2)
  }

  def fromVarMap(src: SMGMonAlertConfSource.Value, srcId: String, vMap: Map[String, String], pluginChecks: Map[String,SMGMonCheck]): Option[SMGMonVarAlertConf] = {
    val pluginCheckKeys = pluginChecks.keySet.map {k => PLUGIN_ALERT_KEY_PX + k}
    val matchingKeys = vMap.keySet.intersect(BUILTIN_ALERT_KEYS ++ pluginCheckKeys)
    if (matchingKeys.isEmpty)
      None
    else {
      val alertWarn = matchingKeys.find(_.startsWith("alert-warn") ).map { k =>
        SMGMonAlertThresh(vMap(k).toDouble, getOp(k))
      }
      val alertCrit = matchingKeys.find(_.startsWith("alert-crit") ).map { k =>
        SMGMonAlertThresh(vMap(k).toDouble, getOp(k))
      }
      val alertPluginChecks = matchingKeys.toSeq.filter(_.startsWith(PLUGIN_ALERT_KEY_PX)).sorted.map { k =>
        val ckId = k.substring(PLUGIN_ALERT_KEY_PX.length)
        SMGMonCheckConf(ckId, vMap(k), pluginChecks(ckId))
      }
      Some(SMGMonVarAlertConf(src, srcId, alertCrit, alertWarn, alertPluginChecks))
    }
  }

  def isAlertKey(k: String): Boolean = k.startsWith("alert-")
}

case class SMGMonObjAlertConf(varConfs: Map[Int, Seq[SMGMonVarAlertConf]]) {
  def varConf(ix: Int): Seq[SMGMonVarAlertConf] = varConfs.getOrElse(ix, Seq())
}
