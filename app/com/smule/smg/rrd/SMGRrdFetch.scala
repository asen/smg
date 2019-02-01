package com.smule.smg.rrd

import com.smule.smg.core.{SMGCmd, SMGObjectView}

import scala.collection.mutable

/**
  * Class encapsulating fetching data from SMGRrdObjects
  *
  * @param rrdConf - rrdtool config
  * @param objv - object config
  */
class SMGRrdFetch(val rrdConf: SMGRrdConfig, val objv: SMGObjectView) {

  import SMGRrd._

  private val FETCH_CF = "AVERAGE"
  private val rrdFname = objv.rrdFile.get

  def fetch(params: SMGRrdFetchParams): List[SMGRrdRow] = {
    val cmd = SMGCmd(fetchCommand(params.resolution, params.period, params.pl))
    val ret = for (ln <- cmd.run
                   if ln != ""
                   if "ds\\d".r.findFirstMatchIn(ln).isEmpty
                   if (!params.filterNan) || "(?i)nan".r.findFirstMatchIn(ln).isEmpty
                   if "^\\s+$".r.findFirstMatchIn(ln).isEmpty
    ) yield {
      val arr0 = ln.trim.split(":",2).map(_.trim)
      val tss = arr0(0).toInt
      val arr = arr0(1).split("\\s+").map(_.trim).map(n =>
        if ("(?i)nan".r.findFirstMatchIn(n).nonEmpty) Double.NaN else n.toDouble)
      //process cdefs
      objv.vars.zipWithIndex.foreach { case (v, i) =>
        if (v.contains("cdef")) {
          arr(i) = computeCdef(v("cdef"), arr(i))
        }
      }
      //process cdefVars
      if (objv.cdefVars.nonEmpty) {
        val cdLst = objv.cdefVars.map {cv => computeRpnValue(cv("cdef"), arr.toList)}
        SMGRrdRow(tss, cdLst)
      } else {
        val filteredArr = if (objv.graphVarsIndexes.nonEmpty) {
          objv.graphVarsIndexes.map(ix => arr(ix)).toArray
        } else arr
        SMGRrdRow(tss, filteredArr.toList)
      }
    }
    reCalcResolution(ret, params.resolution)
  }

  private def fetchCommand(resolution: Option[Int], period: Option[String], pl: Option[String] = None): String = {
    val c = new mutable.StringBuilder(rrdConf.rrdTool).append(" fetch ")
    if (rrdConf.rrdToolSocket.isDefined) {
      c.append(s" --daemon ${rrdConf.rrdToolSocket.get}").append(" ")
    }
    c.append(rrdFname).append(" ").append(FETCH_CF)
    if (resolution.isDefined) c.append(" --resolution=").append(resolution.get)
    if (period.isDefined) c.append(" --start=-").append(safePeriod(period.get))
    if (pl.isDefined) c.append(" --end=start+").append(safePeriod(pl.get))
    c.toString
  }

}


