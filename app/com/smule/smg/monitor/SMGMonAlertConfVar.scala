package com.smule.smg.monitor

import com.smule.smg.core.{SMGLogger, SMGObjectUpdate}

import scala.collection.mutable.ListBuffer

case class SMGMonAlertConfVar(src: SMGMonAlertConfSource.Value,
                              srcId: String,
                              crit: Option[SMGMonAlertThresh],
                              warn: Option[SMGMonAlertThresh],
                              pluginChecks: Seq[SMGMonCheckConf]
                             ) {

  def inspect: String = {
    s"AlertConf: src=$src, srcId=$srcId, crit=$crit, warn=$warn, " +
      s"pluginChecks=[${pluginChecks.map(pc => pc.ckId + ":" + pc.conf).mkString(", ")}]"
  }

  def checkValue(ou: SMGObjectUpdate, vix: Int, ts: Int, newVal: Double): SMGState = {
    var curRet: Option[SMGState] = None
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

object SMGMonAlertConfVar {

  private val log = SMGLogger

  private val BUILTIN_ALERT_KEYS = Set(
    "alert-warn",
    "alert-warn-gte",
    "alert-warn-gt",
    "alert-warn-lte",
    "alert-warn-lt",
    "alert-warn-eq",
    "alert-warn-neq",
    "alert-crit",
    "alert-crit-gte",
    "alert-crit-gt",
    "alert-crit-lte",
    "alert-crit-lt",
    "alert-crit-eq",
    "alert-crit-neq"
  )

  private val PLUGIN_ALERT_KEY_PX = "alert-p-"

  private def getOp(alerKey:String) = {
    val ret = alerKey.split("-")
    if (ret.length < 3) "gte" else ret(2)
  }

  def fromVarMap(src: SMGMonAlertConfSource.Value, srcId: String, vMap: Map[String, String], pluginChecks: Map[String,SMGMonCheck]): Option[SMGMonAlertConfVar] = {
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
      Some(SMGMonAlertConfVar(src, srcId, alertCrit, alertWarn, alertPluginChecks))
    }
  }

  def isAlertKey(k: String): Boolean = k.startsWith("alert-")
}

