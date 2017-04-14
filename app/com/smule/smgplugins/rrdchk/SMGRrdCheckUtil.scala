package com.smule.smgplugins.rrdchk

import com.smule.smg._

import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
  * Created by asen on 3/31/17.
  */

case class SMGRrdVarInfo(
                        index: Int,
                        name: String,
                        rrdType: String,
                        max: Double,
                        min: Double
                        )

case class SMGRrdCheckInfo(ou: SMGObjectUpdate, raw: List[String]) {

  private val kvs = raw.map { ln =>
    val arr = ln.split("\\s*=\\s*", 2)
    if (arr.length == 2) {
      (arr(0), arr(1))
    } else {
      (ln, "")
    }
  }.toMap

  private def dsPrefix(ix: Int) = s"ds[${SMGRrdCheckUtil.dsName(ix)}]"

  private def parseVars = {
    val ret = ListBuffer[SMGRrdVarInfo]()
    var ix = 0
    var px = dsPrefix(ix)
    while (kvs.contains(px + ".index")) {
      ret += SMGRrdVarInfo(
        index = kvs(px + ".index").toInt,
        name = px,
        rrdType = kvs.getOrElse(px + ".type", "ERROR").replaceAll("\"",""),
        max = Try(kvs.getOrElse(px + ".max", "NaN").toDouble).getOrElse(Double.NaN),
        min = Try(kvs.getOrElse(px + ".min", "NaN").toDouble).getOrElse(Double.NaN)
      )
      ix += 1
      px = dsPrefix(ix)
    }
    ret.toList
  }
  val step: Int = kvs.getOrElse("step", "0").toInt
  val vars: List[SMGRrdVarInfo] = parseVars

  def isOk: Boolean = {
    step == ou.interval &&
    vars.size == ou.vars.size &&
    vars.zip(ou.vars).forall { t =>
      val vi = t._1
      val v = t._2
      vi.rrdType == ou.rrdType &&
      vi.max.toString == v.getOrElse("max", "NaN").toDouble.toString &&
      vi.min.toString == v.getOrElse("min", "0.0").toDouble.toString
    }
  }
}

object SMGRrdCheckUtil {

  def rrdInfo(smgConfSvc: SMGConfigService, ou:SMGObjectUpdate): SMGRrdCheckInfo = {
    val cmd = SMGCmd(s"${smgConfSvc.config.rrdConf.rrdTool} info ${ou.rrdFile.get}")
    val raw = try {
      cmd.run
    } catch {
      case c: SMGCmdException => {
        List("ERROR: rrdtool info $rrdFile failed", "--------STDOUT--------", c.stdout, "--------STDERR--------",c.stderr)
      }
      case t: Throwable => {
        List(s"ERROR: rrdtool info ${ou.rrdFile.getOrElse("ERROR_MISSING_FILE")} failed - unexpected error")
      }
    }
    SMGRrdCheckInfo(ou, raw)
  }

  def dsName(vix: Int) = s"ds$vix"

  def rrdTune(smgConfSvc: SMGConfigService, rrdFile: String, tuneWhat: String, vix: Int, newVal: Double): Boolean = {
    val tuneOpt = if (tuneWhat == "min") "-i" else "-a"
    val tuneVal = SMGRrd.numRrdFormat(newVal, nanAsU = true)
    val cmd = SMGCmd(s"${smgConfSvc.config.rrdConf.rrdTool} tune $rrdFile $tuneOpt ${dsName(vix)}:$tuneVal")
    try {
      cmd.run
      true
    } catch {
      case t: Throwable => {
        SMGLogger.ex(t, s"Unexpected error from ${cmd.str}")
        false
      }
    }
  }
}
