package com.smule.smg

import scala.collection.JavaConversions._

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
                        xAgg: Boolean,
                        period: Option[String],
                        desc: Option[String],
                        parentId: Option[String],
                        childIds: Seq[String] = Seq[String](),
                        disableHeatmap: Boolean
                       )  extends SMGIndex {

  /**
    * A helper constructor to buold a SMGConfIndex from given yaml configuration
    * @param id - object id
    * @param yamlMap - yaml Map to build the object from
    */
  def this(id: String, yamlMap: java.util.Map[String,Object]) {
    this(id,
      yamlMap.getOrElse("title", id).toString,
      SMGFilter(
        if (yamlMap.contains("px")) Some(yamlMap("px").toString) else None,
        if (yamlMap.contains("sx")) Some(yamlMap("sx").toString) else None,
        if (yamlMap.contains("rx")) Some(yamlMap("rx").toString) else None,
        if (yamlMap.contains("rxx")) Some(yamlMap("rxx").toString) else None,
        if (yamlMap.contains("trx")) Some(yamlMap("trx").toString) else None,
        if (yamlMap.contains("remote")) Some(yamlMap("remote").toString) else None,
        GraphOptions(
          if (yamlMap.contains("step")) Some(yamlMap("step").asInstanceOf[Int]) else None,
          if (yamlMap.contains("pl")) Some(yamlMap("pl").toString) else None,
          if (yamlMap.contains("xsort")) Some(yamlMap("xsort").asInstanceOf[Int]) else None,
          disablePop = yamlMap.getOrDefault("dpp", "false").toString == "true",
          disable95pRule = yamlMap.getOrDefault("d95p", "false").toString == "true",
          maxY = if (yamlMap.contains("maxy")) Some(yamlMap("maxy").asInstanceOf[Int]) else None
        )
      ),
      if (yamlMap.contains("cols")) Some(yamlMap("cols").asInstanceOf[Int]) else None,
      if (yamlMap.contains("rows")) Some(yamlMap("rows").asInstanceOf[Int]) else None,
      if (yamlMap.contains("agg_op")) Some(yamlMap("agg_op").toString) else None,
      yamlMap.getOrDefault("xagg", "false").toString == "true",
      if (yamlMap.contains("period")) Some(yamlMap("period").toString) else None,
      if (yamlMap.contains("desc")) Some(yamlMap("desc").toString) else None,
      if (yamlMap.contains("parent"))
        Some(yamlMap.get("parent").asInstanceOf[String]) else None,
      if (yamlMap.contains("children"))
        yamlMap.get("children").asInstanceOf[java.util.ArrayList[String]].toList else Seq[String](),
      yamlMap.getOrDefault("dhmap", "false").toString == "true"
    )
  }

  private val periodUrl = if (period.nonEmpty) "&period=" + period.get else ""

  private val colsUrl = if (cols.nonEmpty) "&cols=" + cols.get else ""
  private val rowsUrl = if (rows.nonEmpty) "&rows=" + rows.get else ""

  private def baseUrl = "ix=" + id + colsUrl + rowsUrl

  override def asUrl = baseUrl + periodUrl

  override def asUrlForPeriod(aPeriod: String) = baseUrl + "&period=" + aPeriod

  var childObjs = Seq[SMGIndex]()

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