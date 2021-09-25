package com.smule.smg.core

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
  * A class encapsulating a "Group By" type - grouping a set of compatible graph objects.
  * A group must have the same number of variables and the details included in the
  * grouping can vary according to the groupBy value.
  *
  * @param groupBy - the enum value used when determinig what to group by
  * @param tlMap - "object-level" map - can include rrd type, id suffix etc.
  * @param vars - list - var maps. each map can be the full object var definition or a subset of it,
  *             according to the groupBy value
  */
case class SMGAggGroupBy(groupBy: SMGAggGroupBy.Value, tlMap: Map[String,String], vars:List[SMGObjectVar]){

  def desc: String = {
    val tlDesc = if (tlMap.isEmpty) "" else tlMap.map { case (k, s) => s"$k=$s" }.mkString(" ") + " "
    val varsDesc = s"vars (${vars.size}): " + vars.zipWithIndex.map { case (v, ix) =>
      if (v.m.isEmpty) {
        "(*)"
      } else "(" + v.m.map { case (k,s) =>
        s"$k=$s"
      }.mkString(" ") + ")"
    }.mkString(", ")
    s"Grouped ${SMGAggGroupBy.gbDesc(groupBy)}: " + tlDesc + varsDesc
  }

}

object SMGAggGroupBy extends Enumeration {

  val GB_VARS, GB_OBJLBLS,
  GB_VARSSX, GB_VARSSX2, GB_VARSSX3,
  GB_VARSPX, GB_VARSPX2, GB_VARSPX3,
  GB_VARLBLMU, GB_VARLBLMUSX, GB_NUMV, GB_NUMVSX = Value

  def gbDesc(v: Value): String = v match {
    case GB_VARS => "By same vars definitions (default)"
    case GB_OBJLBLS => "By same object label values"
    case GB_VARSSX => "By same vars defs and object id suffix"
    case GB_VARSSX2 => "By same vars defs and object id suffix (2 levels)"
    case GB_VARSSX3 => "By same vars defs and object id suffix (3 levels)"
    case GB_VARSPX => "By same vars defs and object id prefix"
    case GB_VARSPX2 => "By same vars defs and object id prefix (2 levels)"
    case GB_VARSPX3 => "By same vars defs and object id prefix (3 levels)"
    case GB_VARLBLMU => "By same vars labels and units"
    case GB_VARLBLMUSX => "By same vars labels and units and object id suffix"
    case GB_NUMV => "By same number of vars"
    case GB_NUMVSX => "By same number of vars and suffix"
    case x => throw new RuntimeException(s"The impossible has happened: $x")
  }

  def gbVal(s: String): Option[Value] = Try(withName(s)).toOption

  def gbParamVal(opts: Option[String]): Value = opts.flatMap(s => gbVal(s)).getOrElse(defaultGroupBy)

  def defaultGroupBy: Value = GB_VARS

  def objectGroupByVars(ov: SMGObjectView, gb: Value, gbParam: Option[String]): SMGAggGroupBy = {

    lazy val byLabelsMap = (if (gbParam.getOrElse("") == "") ov.labels else {
      gbParam.get.split("\\s+").map { expr =>
        // assuming just label name for now
        (expr, ov.labels.getOrElse(expr, ""))
      }.toMap
    }).map { case (k,v) => ("label:" + k, v) }

    lazy val labelMuVars = ov.filteredVars(true).map { m =>
      SMGObjectVar(m.m.filter { case (k, v) =>
        (k == "label") || (k == "mu")
      })
    }
    lazy val bySxTlMap = Map("sx" -> ov.id.split('.').last)
    lazy val bySx2TlMap = Map("sx" -> ov.id.split('.').takeRight(2).mkString("."))
    lazy val bySx3TlMap = Map("sx" -> ov.id.split('.').takeRight(3).mkString("."))
    lazy val byPxTlMap = Map("px" -> ov.id.split('.').dropWhile(_.startsWith("@")).head)
    lazy val byPx2TlMap = Map("px" -> ov.id.split('.').dropWhile(_.startsWith("@")).take(2).mkString("."))
    lazy val byPx3TlMap = Map("px" -> ov.id.split('.').dropWhile(_.startsWith("@")).take(3).mkString("."))
    lazy val emptyFilteredVars = ov.filteredVars(true).map { m => SMGObjectVar.empty }

    gb match {
      case GB_VARS => SMGAggGroupBy(gb, Map(), ov.filteredVars(true))
      case GB_OBJLBLS => SMGAggGroupBy(gb, byLabelsMap, emptyFilteredVars)
      case GB_VARSSX => SMGAggGroupBy(gb, bySxTlMap, ov.filteredVars(true))
      case GB_VARSSX2 => SMGAggGroupBy(gb, bySx2TlMap, ov.filteredVars(true))
      case GB_VARSSX3 => SMGAggGroupBy(gb, bySx3TlMap, ov.filteredVars(true))
      case GB_VARSPX => SMGAggGroupBy(gb, byPxTlMap, ov.filteredVars(true))
      case GB_VARSPX2 => SMGAggGroupBy(gb, byPx2TlMap, ov.filteredVars(true))
      case GB_VARSPX3 => SMGAggGroupBy(gb, byPx3TlMap, ov.filteredVars(true))
      case GB_VARLBLMU => SMGAggGroupBy(gb, Map(), labelMuVars)
      case GB_VARLBLMUSX => SMGAggGroupBy(gb, bySxTlMap, labelMuVars)
      case GB_NUMV => SMGAggGroupBy(gb, Map(), emptyFilteredVars)
      case GB_NUMVSX => SMGAggGroupBy(gb, bySxTlMap, emptyFilteredVars)
    }
  }

  def groupByVars(lst: Seq[SMGObjectView], gb: Value, gbParam: Option[String]): Seq[(String, Seq[SMGObjectView])] = {
    // group by vars trying to preserve ordering
    val grouped = mutable.Map[SMGAggGroupBy,Seq[SMGObjectView]](lst.groupBy(ov => objectGroupByVars(ov,gb, gbParam)).toSeq:_*)
    val ret = ListBuffer[(String, Seq[SMGObjectView])]()
    lst.foreach { ov =>
      val gbv = objectGroupByVars(ov, gb, gbParam)
      val elem = grouped.remove(gbv)
      if (elem.isDefined) {
        ret += Tuple2(gbv.desc, elem.get)
      }
    }
    ret.toList
  }
}
