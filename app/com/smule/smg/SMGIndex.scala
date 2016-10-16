package com.smule.smg

/**
  * Created by asen on 11/18/15.
  */

/**
  * A SMG Index interface, representing a filter and view properties to use when displaying
  * Can have a parent and a set of child indexes. Index without a parent is considered Top Level
  */
trait SMGIndex {
  /**
    * index id
    */
  val id:String

  /**
    * index title
    */
  val title: String

  /**
    * Index filter
    */
  val flt: SMGFilter

  /**
    * number of rows to display results in
    */
  val cols: Option[Int]
  /**
    * max rows to display results in (excess rows are paginated)
    */
  val rows: Option[Int]

  /**
    * Optional parent id string. None if top-level
    */
  val parentId: Option[String]

  /**
    * A set of pre-defined child index ids of this index
    */
  val childIds: Seq[String]

  /**
    * get the url representation of this index for the default period
    * @return
    */
  def asUrl:String

  /**
    * get the url representation of this index for specific period
    * @return
    */
  def asUrlForPeriod(aPeriod: String): String

  /**
    * all children indexes of this index - including ones specified via childIds and
    * ones specifying this id as parent
    * @return - sequence of child indexes
    */
  def children: Seq[SMGIndex]

  val aggOp: Option[String]

  val xAgg: Boolean

  val period: Option[String]
  val desc: Option[String]

  val disableHeatmap: Boolean
}
