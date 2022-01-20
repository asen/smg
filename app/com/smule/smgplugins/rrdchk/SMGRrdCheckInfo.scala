package com.smule.smgplugins.rrdchk

import com.smule.smg.core.SMGObjectUpdate
import com.smule.smg.plugin.SMGPluginLogger
import com.smule.smg.rrd.{SMGRraDef, SMGRraInfo}

import scala.collection.mutable.ListBuffer
import scala.util.Try

case class SMGRrdCheckInfo(ou: SMGObjectUpdate, raw: List[String]) {

  private val log = new SMGPluginLogger("rrdchk") // TODO hard-coded plugin id

  private val kvs = raw.map { ln =>
    val arr = ln.split("\\s*=\\s*", 2)
    if (arr.length == 2) {
      (arr(0), arr(1))
    } else {
      (ln, "")
    }
  }.toMap

  private def dsPrefix(ix: Int) = s"ds[${SMGRrdCheckUtil.dsName(ix)}]"

  private def parseVars: List[SMGRrdVarInfo] = {
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

  private def parseRras: List[SMGRraInfo] = {
//    rra[0].cf = "AVERAGE"
//    rra[0].rows = 5760
//    rra[0].pdp_per_row = 1

    val ret = ListBuffer[SMGRraInfo]()
    val byRraIx = kvs.groupBy { case (k,v) =>
      if (k.startsWith("rra[")){
        Try(k.split('[')(1).split(']')(0).toInt).toOption
      } else None
    }
    byRraIx.keys.foreach { ixOpt =>
      if (ixOpt.isDefined) {
        val m = byRraIx(ixOpt)
        val ix = ixOpt.get
        val cfKey = s"rra[$ix].cf"
        val rowsKey = s"rra[$ix].rows"
        val pdpPerRowKey = s"rra[$ix].pdp_per_row"
        if (m.contains(cfKey) && m.contains(pdpPerRowKey) && m.contains(rowsKey)){
          ret += SMGRraInfo(cf = m(cfKey).replaceAll("\"", ""), pdpPerRow = m(pdpPerRowKey).toInt, rows = m(rowsKey).toInt)
        }
      }
    }
    ret.sortBy(_.sortTuple).toList
  }

  val step: Int = kvs.getOrElse("step", "0").toInt
  val vars: List[SMGRrdVarInfo] = parseVars
  val infoRras: List[SMGRraInfo] = parseRras
  val ouRras: List[SMGRraInfo] =
    ou.rraDef.getOrElse(SMGRraDef.getDefaultRraDef(ou.interval, ou.rraCfs)).parsedDefsAll.sortBy(_.sortTuple)

  def isOkIntervalMinMax: Boolean = {
    step == ou.interval &&
      vars.size == ou.vars.size &&
      vars.zip(ou.vars).forall { t =>
        val vi = t._1
        val v = t._2
        vi.rrdType == ou.rrdType &&
          vi.max.toString == Try(v.max.getOrElse("NaN").toDouble).getOrElse(Double.NaN).toString &&
          vi.min.toString == Try(v.min.getOrElse("0.0").toDouble).getOrElse(Double.NaN).toString
      }
  }

  def isOkRras: Boolean = {
    (ouRras == infoRras) && vars.forall { vi => vi.rrdType == ou.rrdType }
  }

  def isOk: Boolean = isOkIntervalMinMax && isOkRras
}

