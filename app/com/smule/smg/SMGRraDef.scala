package com.smule.smg

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * Created by asen on 12/13/15.
  */
case class SMGRraDef(rraId: String, defs: Seq[String]) {

  //  - "RRA:AVERAGE:0.5:1:5760"
  //  - "RRA:AVERAGE:0.5:5:2304"
  // ...
  //  - "RRA:MAX:0.5:1:5760"
  //  - "RRA:MAX:0.5:5:2304"
  // ...

  private val refCf = "AVERAGE" // TODO XXX using only the "AVERAGE" rras for now

  case class RraInfo(cf: String, pdpPerRow: Int, rows: Int) {
    def step(interval: Int): Int = interval * pdpPerRow
    def maxPeriod(interval: Int): Int = step(interval) * rows
  }

  private def parseRraInfo(rra: String): RraInfo = {
    val arr = rra.split(":")
    RraInfo(
      cf = arr.lift(1).getOrElse(refCf),
      pdpPerRow = arr.lift(3).map(_.toInt).getOrElse(1),
      rows = arr.lift(4).map(_.toInt).getOrElse(0)
    )
  }

  lazy val parsedDefs: Seq[RraInfo] = defs.map(parseRraInfo).groupBy(_.cf).getOrElse(refCf, Seq()).
    sortBy(ri => ri.pdpPerRow * ri.rows)

  def findMinStepAt(period: Int, interval: Int): Option[Int] = {
    parsedDefs.find { ri =>
      period <= ri.maxPeriod(interval)
    }.map(_.pdpPerRow * interval)
  }
}

object SMGRraDef {

  private def rrdMinutesSteps(v:Int, interval: Int):Int = (v * 60) / interval
  private def rrdDaysRows(v:Int, steps: Int, interval: Int):Int = {
    val mySteps = if (steps == 0) 1 else steps
    ((3600 * 24) / (interval * mySteps)) * v
  }

  private def createDefaultRraDef(rraId: String, interval: Int): SMGRraDef = {
    val lst = ListBuffer[String]()
    Seq("AVERAGE", "MAX").foreach{ cf =>
      if (interval <= 60) {
        lst += s"RRA:$cf:0.5:1:" + rrdDaysRows(4, 1, interval)
        //if (rrdMinutesSteps(5,interval) > 1) // only applcable on 1 min interval
        lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(5,interval) + ":" + rrdDaysRows(8, rrdMinutesSteps(5,interval),interval)    //5M:8d")
      } else
        lst += s"RRA:$cf:0.5:1:" + rrdDaysRows(8,1,interval)
      if (rrdMinutesSteps(30,interval) > 0)
        lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(30,interval) + ":" + rrdDaysRows(28, rrdMinutesSteps(30,interval),interval)    //30M:4w")
      if (rrdMinutesSteps(120,interval) > 0)
        lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(120,interval) + ":" + rrdDaysRows(120, rrdMinutesSteps(120,interval),interval) //2h:120d")
      if (rrdMinutesSteps(360,interval) > 0)
        lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(360,interval) + ":" + rrdDaysRows(730, rrdMinutesSteps(360,interval),interval) //6h:2y")
      lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(1440,interval) + ":" + rrdDaysRows(1460, rrdMinutesSteps(1440,interval),interval) //24h:4y")
    }
    SMGRraDef(rraId, lst.toList)
  }

  private val defaultRraDefs = mutable.Map[String, SMGRraDef]()

  def getDefaultRraDef(interval: Int): SMGRraDef = {
    val rraId = "_default-" + interval
    defaultRraDefs.getOrElseUpdate(rraId, { createDefaultRraDef(rraId, interval) })
  }
}
