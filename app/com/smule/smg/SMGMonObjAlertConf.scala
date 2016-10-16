package com.smule.smg

import play.api.libs.json.{JsValue, Json}

/**
  * Created by asen on 7/7/16.
  */

case class SMGMonAlertThresh(value: Double, op: String) {

  def checkAlert(fetchedValue: Double):Option[String] = {
    op match {
      case "gte" => if (fetchedValue >= value) Some(s"$fetchedValue >= $value") else None
      case "gt"  => if (fetchedValue > value) Some(s"$fetchedValue > $value") else None
      case "lte" => if (fetchedValue <= value) Some(s"$fetchedValue <= $value") else None
      case "lt"  => if (fetchedValue < value) Some(s"$fetchedValue < $value") else None
      case "eq"  => if (fetchedValue == value) Some(s"$fetchedValue == $value") else None
      case badop: String => {
        SMGLogger.warn("SMGMonAlertThresh: invalid op: " + badop)
        None
      }
    }
  }
}

object SMGMonSpikeThresh {
  val DEFAULT_SPIKE_CHECK_CONF = "1.5:30m:30h".split(":")
}

case class SMGMonSpikeThresh(confStr: String) {

  // <changeThresh>:<shortPeriod>:<longPeriod>
  private val arr0 = confStr.split(":")
  private val configArr = if (arr0.length < 3) SMGMonSpikeThresh.DEFAULT_SPIKE_CHECK_CONF else arr0

  val changeThresh = configArr(0).toDouble // = 1.5
  val maxStCntStr = configArr(1)           // = "30m",
  val maxLtCntStr = configArr(2)           // = "30h"

  private def parsePeriodStr(periodStr: String) = SMGRrd.parsePeriod(periodStr).getOrElse(3600)

  def maxStCnt(interval: Int) = scala.math.max( parsePeriodStr(maxStCntStr) / interval, 2)

  def maxLtCnt(interval: Int) = scala.math.max( parsePeriodStr(maxLtCntStr) / interval, 2) + maxStCnt(interval)

  def checkAlert(mvstats: SMGMonValueMovingStats, maxStCnt: Int, maxLtCnt: Int): Option[String] = {
    mvstats.checkAnomaly(changeThresh, maxStCnt, maxLtCnt).map(s => s"($maxStCntStr/$maxLtCntStr) $s")
  }
}

object SMGMonAlertConfSource extends Enumeration {
  val OBJ, INDEX, HINDEX = Value
}

case class SMGMonVarAlertConf(src: SMGMonAlertConfSource.Value,
                              srcId: String,
                              crit: Option[SMGMonAlertThresh],
                              warn: Option[SMGMonAlertThresh],
                              spike: Option[SMGMonSpikeThresh]
                             )

object SMGMonVarAlertConf {

  val log = SMGLogger

  private val ALERT_KEYS = Set(
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
    "alert-crit-eq",
    "alert-spike"
  )

  private def getOp(alerKey:String) = {
    val ret = alerKey.split("-")
    if (ret.length < 3) "gte" else ret(2)
  }

  def fromVarMap(src: SMGMonAlertConfSource.Value, srcId: String, vMap: Map[String, String]): Option[SMGMonVarAlertConf] = {
    val matchingKeys = vMap.keySet.intersect(ALERT_KEYS)
    if (matchingKeys.isEmpty)
      None
    else {
      val alertWarn = matchingKeys.find(_.startsWith("alert-warn") ).map { k =>
        SMGMonAlertThresh(vMap(k).toDouble, getOp(k))
      }
      val alertCrit = matchingKeys.find(_.startsWith("alert-crit") ).map { k =>
        SMGMonAlertThresh(vMap(k).toDouble, getOp(k))
      }
      val alertSpike = matchingKeys.find(_.startsWith("alert-spike") ).map { k =>
        SMGMonSpikeThresh(vMap(k))
      }
      Some(SMGMonVarAlertConf(src, srcId, alertCrit, alertWarn, alertSpike))
    }
  }

  def isAlertKey(k: String) = k.startsWith("alert-")
}

case class SMGMonObjAlertConf(private val varConfs: Map[Int, Seq[SMGMonVarAlertConf]]) {
  def varConf(ix: Int): Seq[SMGMonVarAlertConf] = varConfs.getOrElse(ix, Seq())
}
