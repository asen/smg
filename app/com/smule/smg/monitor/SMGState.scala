package com.smule.smg.monitor

import java.text.{DecimalFormat, SimpleDateFormat}
import java.util.Date

import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, Reads}
import com.smule.smg._
import com.smule.smg.rrd.SMGRrd

object SMGState extends Enumeration {

  // Sorted by severity
  val OK, ANOMALY, WARNING, UNKNOWN, CRITICAL, SMGERR = Value

  val hmsTimeFormat = new SimpleDateFormat("HH:mm:ss")

  val shortTimeFormat = new SimpleDateFormat("MMM d HH:mm:ss")

  val longTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  val YEAR_SECONDS: Int = 365 * 24 * 3600  //roughly
  val DAY_SECONDS: Int = 24 * 3600

  /**
    * withName is final so can not be overriden - using this to allow backwards compatibility with old names
    * calls withName unless known old name
    * @param nm - the string value name
    * @return
    */
  def fromName(nm: String): SMGState.Value = {
    // XXX backwards compatibility with old value names
    nm match {
      case "E_ANOMALY" => this.ANOMALY
      case "E_VAL_WARN" => this.WARNING
      case "E_FETCH" => this.UNKNOWN
      case "E_VAL_CRIT" => this.CRITICAL
      case "E_SMGERR" => this.SMGERR
      case _ => this.withName(nm)
    }
  }

  def formatTss(ts: Int): String = {
    val tsDiff = Math.abs(tssNow - ts)
    val myFmt = if (tsDiff < DAY_SECONDS)
      hmsTimeFormat
    else  if (tsDiff < YEAR_SECONDS)
      shortTimeFormat
    else
      longTimeFormat
    myFmt.format(new Date(ts.toLong * 1000))
  }

  def formatDuration(duration: Int): String = {
    var tsDiff = duration
    val sb = new StringBuilder()
    if (tsDiff >  YEAR_SECONDS) {
      sb.append(s"${tsDiff / YEAR_SECONDS}y")
      tsDiff = tsDiff % YEAR_SECONDS
    }
    if (tsDiff > DAY_SECONDS) {
      sb.append(s"${tsDiff / DAY_SECONDS}d")
      tsDiff = tsDiff % DAY_SECONDS
    }
    if (tsDiff > 3600) {
      sb.append(s"${tsDiff / 3600}h")
      tsDiff = tsDiff % 3600
    }
    sb.append(s"${tsDiff / 60}m")
    sb.toString()
  }

  private val mySmallFormatter = new DecimalFormat("#.######")
  private val myBigFormatter = new DecimalFormat("#.###")

  def numFmt(num: Double, mu: Option[String]): String = if (num.isNaN) "NaN" else {
    val absNum = math.abs(num)
    val (toFmt, metricPrefix, myFormatter) = if (absNum >= 1000000000) {
      (num / 1000000000, "G", myBigFormatter)
    } else if (absNum >= 1000000) {
      (num / 1000000, "M", myBigFormatter)
    } else if (absNum >= 1000) {
      (num / 1000, "K", myBigFormatter)
    } else if (absNum >= 1) {
      (num, "", myBigFormatter)
    } else {
      (num, "", mySmallFormatter)
    }
    myFormatter.format(toFmt) + metricPrefix + mu.getOrElse("")
  }


  private val severityChars = Map(
    0 -> "",   // OK
    1 -> "~",  // ANOMALY
    2 -> "^",  // WARNING
    3 -> "?",  // UNKNOWN
    4 -> "!",  // CRITICAL
    5 -> "e"   // SMGERR
  )

  private val severityTextColors = Map(
    0 -> "white",  // OK
    1 -> "black",  // ANOMALY
    2 -> "black",  // WARNING
    3 -> "white",  // UNKNOWN
    4 -> "white",  // CRITICAL
    5 -> "white"   // SMGERR
  )

  def stateChar(stateIx: Int): String = severityChars.getOrElse(stateIx, "e")

  def stateTextColor(stateIx: Int): String = severityTextColors.getOrElse(stateIx, "black")

  val okColor = "#009900"
  val colorsSeq = Seq(
    0.5 -> "#00e600",
    1.0 -> "#33cccc",
    1.5 -> "#0099cc",
    2.0 -> "#e6e600",
    2.5 -> "#ffff00",
    3.0 -> "#ff9900",
    3.5 -> "#cc7a00",
    4.0 -> "#ff3300",
    5.0 -> "#ff0000"
  )

  val colorsMap: Map[Double, String] = (Seq(0.0 -> okColor) ++ colorsSeq).toMap

  def stateColor(stateIx: Int): String =  colorsMap.getOrElse(stateIx.toDouble, "#000000")

  def tssNow: Int = SMGRrd.tssNow //(System.currentTimeMillis() / 1000).toInt

  val smgStateReads: Reads[SMGState] = {
    //SMGState(ts: Int, state: SMGState.Value, desc: String)
    (
      (JsPath \ "ts").read[Int] and
        (JsPath \ "state").read[String].map(s => SMGState.fromName(s)) and
        (JsPath \ "desc").read[String]
      ) (SMGState.apply _)
  }

  def initialState = SMGState(SMGState.tssNow, SMGState.OK, "Initial state")
}

case class SMGState(ts: Int, state: SMGState.Value, desc: String) {
  def timeStr: String = SMGState.formatTss(ts)
  def charRep: String = SMGState.stateChar(state.id)
  def stateColor: String = SMGState.stateColor(state.id)
  def textColor: String = SMGState.stateTextColor(state.id)
  val isOk: Boolean = state == SMGState.OK
}
