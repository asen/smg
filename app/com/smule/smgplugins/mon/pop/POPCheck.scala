package com.smule.smgplugins.mon.pop

import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGLoggerApi, SMGObjectUpdate, SMGObjectView}
import com.smule.smg.monitor._
import com.smule.smg.rrd.{SMGRrd, SMGRrdFetch, SMGRrdFetchParams}
import com.smule.smgplugins.mon.common.MonCheckDefs

import scala.collection.concurrent.TrieMap
import scala.util.Try

/*
 * PeriodOverPeriod (POP) check
 */

case class POPCheckThreshConf(confStr: String) {
  //alert-p-mon-pop: "24h-5m:lt:0.7"
  //alert-p-mon-pop: "24h-1m:lt::0.5"

  private val arr = confStr.split(":")
  private val periodResArr = arr(0).split("-", 2)

  val period: Int = SMGRrd.parsePeriod(periodResArr(0)).getOrElse(60) // TODO

  val res: Option[Int] = if (periodResArr.isDefinedAt(1)) SMGRrd.parsePeriod(periodResArr(1)) else None

  val op: String = arr.lift(1).filter(MonCheckDefs.COMPARISON_OPS.contains).getOrElse("lt")

  private val checkFn: (Double, Double, Double) => Boolean = {
    op match {
      case "gte" =>
        (cur: Double, prev: Double, thresh: Double) => cur >= prev * thresh
      case "gt" =>
        (cur: Double, prev: Double, thresh: Double) => cur > prev * thresh
      case "eq" =>
        (cur: Double, prev: Double, thresh: Double) => cur == prev * thresh
      case "lte" =>
        (cur: Double, prev: Double, thresh: Double) => cur <= prev * thresh
      case _  => //"lt"
        (cur: Double, prev: Double, thresh: Double) => cur < prev * thresh
    }
  }

  val warnThresh: Option[Double] = arr.lift(2).flatMap(s => Try(s.toDouble).toOption)
  val critThresh: Option[Double] = arr.lift(3).flatMap(s => Try(s.toDouble).toOption)

  def checkAlert(cur: Double, prev: Double, thresh: Option[Double],
                 numFmt: (Double) => String): Option[String]= {
    if (thresh.isDefined && checkFn(cur, prev, thresh.get)) {
      Some(s"c=${numFmt(cur)} $op p=${numFmt(prev)} * ${thresh.get} ($confStr)")
    } else None
  }

}

class POPCheck(val ckId: String, log: SMGLoggerApi, configSvc: SMGConfigService) extends SMGMonCheck {

  private val threshConfs = TrieMap[String, POPCheckThreshConf]()

  private def confFromStr(confStr: String): POPCheckThreshConf = {
    threshConfs.getOrElseUpdate(confStr, { POPCheckThreshConf(confStr) })
  }

  override def checkValue(ou: SMGObjectUpdate, vix: Int, ts: Int, newValUnused: Double, checkConf: String): SMGState = {
    val checkConfThresh = confFromStr(checkConf)
    val curPrevVals = getCurPrevVals(ts, ou, vix, checkConfThresh.period, checkConfThresh.res)
    val newVal = curPrevVals._1
    val prevVal = curPrevVals._2
    def numFmt = { (d: Double) => ou.numFmt(d, vix, applyCdef = false)}
    val critDesc = checkConfThresh.checkAlert(newVal, prevVal, checkConfThresh.critThresh, numFmt)
    if (critDesc.isDefined){
      return SMGState(ts, SMGState.CRITICAL, critDesc.get)
    }
    val warnDesc = checkConfThresh.checkAlert(newVal, prevVal, checkConfThresh.warnThresh, numFmt)
    if (warnDesc.isDefined){
      return SMGState(ts, SMGState.WARNING, warnDesc.get)
    }
    SMGState(ts, SMGState.OK, s"c=${numFmt(newVal)} p=${numFmt(prevVal)} (${checkConfThresh.confStr})")
  }

  def getCurPrevVals(ts: Int, ou: SMGObjectUpdate, vix: Int, period: Int, res: Option[Int]): (Double, Double)= {
    val fetchSince = period //+ res.getOrElse(ou.interval)
    val ov = ou.asInstanceOf[SMGObjectView] // XXX TODO?
    val f = new SMGRrdFetch(configSvc.config.rrdConf, ov)
    val params = SMGRrdFetchParams(res, Some(fetchSince.toString), None, filterNan = false)
    val rawFdata = f.fetch(params)
    // chop the last NaN if any
    val fdata = if (rawFdata.lastOption.exists(r => r.vals(vix).isNaN))
      rawFdata.dropRight(1)
    else
      rawFdata
    if (fdata.isEmpty)
      return (Double.NaN, Double.NaN)
    if (fdata.tail.isEmpty)
      return (fdata.head.vals(vix), Double.NaN)
    val prevVal = if (ts - fdata.head.tss < period - res.getOrElse(ou.interval))
      Double.NaN
    else
      fdata.head.vals(vix)
    val curVal = fdata.last.vals(vix)
    (curVal, prevVal)
  }

  def cleanupObsoleteStates(pluginId: String): Unit = {
    threshConfs.clear()
  }

  override def inspectState(ou: SMGObjectUpdate, vix: Int, checkConf: String): String = {
    checkValue(ou, vix, SMGRrd.tssNow, 0.0, checkConf).toString
  }

}
