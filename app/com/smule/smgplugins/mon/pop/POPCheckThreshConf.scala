package com.smule.smgplugins.mon.pop

import com.smule.smg.rrd.SMGRrd
import com.smule.smgplugins.mon.common.MonCheckDefs

import scala.util.Try

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

