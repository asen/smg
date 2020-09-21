package com.smule.smg.config

import com.smule.smg.core.{SMGAggGroupBy, SMGFilter, SMGIndex}
import com.smule.smg.grapher.GraphOptions

import scala.collection.JavaConverters._

/**
  * Created by asen on 11/15/15.
  */

/**
  * Class representing an Index defined in the yaml configuration
  * @param id - unique id of this index
  * @param title - display title of the index
  * @param flt - a SMGFilter to use when graphing index graphs
  * @param cols - number of columns to display graphs in
  * @param rows - max number of rows to display (excess rows are paginated)
  * @param aggOp - optional aggregate op to use (STACK, SUM etc)
  * @param parentId - an optional parent index id (an index is considered Top Level if this is None)
  * @param childIds - an optional sequence of child index ids for this index
  */
case class SMGConfIndex(id: String,
                        title: String,
                        flt: SMGFilter,
                        cols: Option[Int],
                        rows: Option[Int],
                        aggOp: Option[String],
                        xRemoteAgg: Boolean,
                        aggGroupBy: Option[SMGAggGroupBy.Value],
                        gbParam: Option[String],
                        period: Option[String],
                        desc: Option[String],
                        parentId: Option[String],
                        childIds: Seq[String] = Seq[String](),
                        disableHeatmap: Boolean
                       )  extends SMGIndex {

  /**
    * A helper constructor to build a SMGConfIndex from given yaml configuration
    * @param id - object id
    * @param yamlMap - yaml Map to build the object from
    */
  def this(id: String, yamlMap: Map[String,Object]) {
    this(id,
      yamlMap.getOrElse("title", id).toString,
      SMGFilter.fromYamlMap(yamlMap),
      if (yamlMap.contains("cols")) Some(yamlMap("cols").asInstanceOf[Int]) else None,
      if (yamlMap.contains("rows")) Some(yamlMap("rows").asInstanceOf[Int]) else None,
      if (yamlMap.contains("agg_op")) Some(yamlMap("agg_op").toString) else None,
      yamlMap.getOrElse("xagg", "false").toString == "true",
      if (yamlMap.contains("gb")) SMGAggGroupBy.gbVal(yamlMap("gb").toString) else None,
      if (yamlMap.contains("gbp")) Some(yamlMap("gbp").toString) else None,
      if (yamlMap.contains("period")) Some(yamlMap("period").toString) else None,
      if (yamlMap.contains("desc")) Some(yamlMap("desc").toString) else None,
      if (yamlMap.contains("parent"))
        Some(yamlMap("parent").asInstanceOf[String]) else None,
      if (yamlMap.contains("children"))
        yamlMap("children").asInstanceOf[java.util.ArrayList[String]].asScala else Seq[String](),
      yamlMap.getOrElse("dhmap", "false").toString == "true"
    )
  }

  private val periodUrl = if (period.nonEmpty) "&period=" + period.get else ""

  private val colsUrl = if (cols.nonEmpty) "&cols=" + cols.get else ""
  private val rowsUrl = if (rows.nonEmpty) "&rows=" + rows.get else ""

  private def baseUrl = "ix=" + id + colsUrl + rowsUrl

  override def asUrl: String = baseUrl + periodUrl

  override def asUrlForPeriod(aPeriod: String): String = baseUrl + "&period=" + aPeriod

  private var childObjs: Seq[SMGIndex] = Seq[SMGIndex]()

  override def children: Seq[SMGIndex] = childObjs
}

object SMGConfIndex {

  /**
    * Build the parent/children structure of given sequence of SMGIndexes (based on parentid and childIds)
    * @param allIxes - the list of all indexes to update
    */
  def buildChildrenSubtree(allIxes: Seq[SMGConfIndex]):Unit = {
    val ixesById = allIxes.groupBy(_.id)
    for (ix <- allIxes) {
      ix.childObjs = (for (cid <- ix.childIds ; ixLstOpt = ixesById.get(cid) ; if ixLstOpt.isDefined ; if ixLstOpt.get.nonEmpty)
        yield ixLstOpt.get.head).toList
    }
  }

}