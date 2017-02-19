package com.smule.smg

/**
  * Created by asen on 12/4/15.
  */


case class SMGRemoteObject(
                            id: String,
                            interval: Int,
                            vars: List[Map[String, String]],
                            cdefVars: List[Map[String, String]],
                            graphVarsIndexes: Seq[Int],
                            title: String,
                            stack: Boolean,
                            rrdType: String
                          ) extends SMGObjectView {


  /**
    * The "show" url for this object
    *
    * @return - a string representing an url to display this object details
    */
  def showUrl:String = "/show/" + id

  def fetchUrl(period: String):String = "/fetch/" + id + "?s=" + period

  override val rrdFile: Option[String] = None
  override val isAgg: Boolean = false
  override val refObj: Option[SMGObjectUpdate] = None
}

/**
  * local copy of a remote object (one with fetched .rrd file)
  * @param id
  * @param interval
  * @param vars
  * @param cdefVars
  * @param graphVarsIndexes
  * @param title
  * @param stack
  * @param rrdFile
  */
case class SMGRemoteObjectCopy(
                            id: String,
                            interval: Int,
                            vars: List[Map[String, String]],
                            cdefVars: List[Map[String, String]],
                            graphVarsIndexes: Seq[Int],
                            title: String,
                            stack: Boolean,
                            rrdFile: Option[String],
                            rrdType: String
                          ) extends SMGObjectView {

  def this(robj:SMGObjectView, rrdFile: String) = this(robj.id,
    robj.interval,
    robj.vars,
    robj.cdefVars,
    robj.graphVarsIndexes,
    robj.title,
    robj.stack,
    Some(rrdFile),
    robj.rrdType
  )

  /**
    * The "show" url for this object
    * @return - a string representing an url to display this object details
    */
  def showUrl:String = "/show/" + id

  def fetchUrl(period: String):String = "/fetch/" + id + "?s=" + period

  override val isAgg: Boolean = false

  override val refObj: Option[SMGObjectUpdate] = None
}


case class SMGRemoteAggObject(
                            id: String,
                            objs: Seq[SMGObjectView],
                            op: String,
                            vars: List[Map[String, String]],
                            cdefVars: List[Map[String, String]],
                            graphVarsIndexes: Seq[Int],
                            title: String
                          ) extends SMGAggObjectView {
  override val refObj: Option[SMGObjectUpdate] = None

  override lazy val rrdType: String = objs.map(_.rrdType).distinct.mkString(",")
}
