package com.smule.smgplugins.mon.ex

import java.time.Instant
import java.util.{Calendar, Date}

import com.smule.smg.SMGRrd

import scala.util.Try

case class ExtendedCheckConf(confStr: String) {
  // warn thresh:crit thresh:time_frame
  // alert-p-mon-ex: "24h:lt-0.7:lt-0.5:18_00-20_00*mon"

  private val confArr = confStr.split(":")
  private val warnConfStr = confArr.lift(1).getOrElse("")
  private val critConfStr = confArr.lift(2).getOrElse("")
  private val activeTimeStr = confArr.lift(3).getOrElse("")

  val fetchStep: Option[Int] = SMGRrd.parsePeriod(confArr(0))

  private def parseCheckTuple(confToken: String): Option[(String,Double)] = {
    if (confToken == "") None else {
      val arr = confToken.split("-", 2)
      if (arr.length != 2) None else {
        val op = arr(0) // TODO validate op
        val thresh = Try(arr(1).toDouble).toOption
        if (thresh.isEmpty) None else {
          Some((op, thresh.get))
        }
      }
    }
  }

  /* private */ val warnCheck: Option[(String, Double)] = parseCheckTuple(warnConfStr)
  /* private */ val critCheck: Option[(String, Double)] = parseCheckTuple(critConfStr)

  private def checkValue(op: String, thresh: Double, cur: Double): Boolean = op match {
    case "gte" => cur >= thresh
    case "gt" => cur > thresh
    case "eq" => cur == thresh
    case "lte" => cur <= thresh
    case _  => //"lt"
      cur < thresh
  }

  case class ExCheckActivePeriod(periodConfStr: String) {

    private val weekDaysCal = Array(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                                    Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
    private val weekDays = Array("sun", "mon", "tue", "wed", "thu", "fri", "sat", "sun")

    // 18_00-20_00
    // 18_00-20_00*mon
    // 18_00-20_00*1

    private val arr = periodConfStr.split("\\*", 2)
    private val (hourStr, dayStr) = if (arr.length == 2) (arr(0), Some(arr(1))) else (arr(0), None)

    /* private */ val dayOfWeek: Option[Int] = dayStr.flatMap { ds =>
      val ixOf = weekDays.indexOf(ds.take(3).toLowerCase)
      if (ixOf < 0) None else Some(weekDaysCal(ixOf))
    }

    /* private */ val dayOfMonth: Option[Int] = dayStr.flatMap { ds =>
      if (dayOfWeek.isDefined) None else Try(ds.toInt).toOption
    }

    private val (startStr, endStr) = if (hourStr == "") (None, None) else {
      val arr = hourStr.split("-")
      if (arr.length != 2)
        (None, None)
      else (Some(arr(0)), Some(arr(1)))
    }

    private def parseTimeOfDay(s: String): (Option[Int],Option[Int]) = {
      // 18_00
      val arr = s.split("_")
      val hour = Try(arr(0).toInt).toOption
      val min = arr.lift(1).flatMap(m => Try(m.toInt).toOption)
      (hour, min)
    }

    /* private */ val startHourMinute: Option[(Option[Int], Int)] = startStr.map(parseTimeOfDay).map { case (oh,om) =>
      (oh, om.getOrElse(0))
    }

    /* private */ val endHourMinute: Option[(Option[Int], Int)] = endStr.map(parseTimeOfDay).map { case (oh,om) =>
      (oh, om.getOrElse(0))
    }

    def isActiveAt(ts: Option[Int]): Boolean = {
      val cts = if (ts.isDefined) ts.get.toLong * 1000 else System.currentTimeMillis()
      lazy val ctsDt = Date.from(Instant.ofEpochMilli(cts))
      lazy val ctsCal = Calendar.getInstance()
      ctsCal.setTime(ctsDt)
      val activeDay = (dayOfWeek.isEmpty && dayOfMonth.isEmpty) ||
        (dayOfWeek.isDefined && dayOfWeek.get == ctsCal.get(Calendar.DAY_OF_WEEK)) ||
        (dayOfMonth.isDefined && dayOfMonth.get == ctsCal.get(Calendar.DAY_OF_MONTH))
      lazy val activeHourMinute = startHourMinute.isEmpty || endHourMinute.isEmpty || {
        val sh = startHourMinute.get._1.getOrElse(0)
        val sm = startHourMinute.get._2
        val eh = endHourMinute.get._1.getOrElse(24)
        val em = endHourMinute.get._2
        val ctsMin = ctsCal.get(Calendar.MINUTE)
        val ctsHr = ctsCal.get(Calendar.HOUR)
        val afterStart = (sh < ctsHr) || ((sh == ctsHr) && (sm <= ctsMin))
        val beforeEnd =   (ctsHr < eh) || ((ctsHr == eh) && (ctsMin <= em))
        afterStart && beforeEnd
      }
      activeDay && activeHourMinute
    }
  }

  /* private */ val activeCheckPeriods: Seq[ExCheckActivePeriod] = if (activeTimeStr == "") Seq() else {
    activeTimeStr.split(",").map(s => ExCheckActivePeriod(s))
  }

  def isActiveAt(ts: Option[Int]): Boolean = activeCheckPeriods.isEmpty || activeCheckPeriods.exists(_.isActiveAt(ts))

  def checkWarn(cur: Double): Boolean = if (warnCheck.isEmpty)
    false
  else
    checkValue(warnCheck.get._1, warnCheck.get._2, cur)

  def checkCrit(cur: Double): Boolean = if (critCheck.isEmpty)
    false
  else
    checkValue(critCheck.get._1, critCheck.get._2, cur)
}
