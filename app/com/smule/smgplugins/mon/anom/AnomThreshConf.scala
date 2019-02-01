package com.smule.smgplugins.mon.anom

import com.smule.smg.rrd.SMGRrd

object AnomThreshConf {
  val DEFAULT_SPIKE_CHECK_CONF: Array[String] = "1.5:30m:30h".split(":")
}

case class AnomThreshConf(confStr: String) {

  // <changeThresh>:<shortPeriod>:<longPeriod>
  private val arr0 = confStr.split(":")
  private val configArr = if (arr0.length < 3) AnomThreshConf.DEFAULT_SPIKE_CHECK_CONF else arr0

  val changeThresh: Double = configArr(0).toDouble // = 1.5
  val maxStCntStr: String = configArr(1)           // = "30m",
  val maxLtCntStr: String = configArr(2)           // = "30h"

  private def parsePeriodStr(periodStr: String) = SMGRrd.parsePeriod(periodStr).getOrElse(3600)

  def maxStCnt(interval: Int): Int = scala.math.max( parsePeriodStr(maxStCntStr) / interval, 2)

  def maxLtCnt(interval: Int): Int = scala.math.max( parsePeriodStr(maxLtCntStr) / interval, 2) + maxStCnt(interval)

  def checkAlert(mvstats: ValueMovingStats, interval:Int,  maxStCnt: Int, maxLtCnt: Int, numFmt: (Double) => String): Option[String] = {
    mvstats.checkAnomaly(interval, changeThresh, maxStCnt, maxLtCnt, numFmt).map(s => s"($maxStCntStr/$maxLtCntStr) $s")
  }
}

