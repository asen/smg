package com.smule.smg

/**
  * Created by asen on 12/4/15.
  */

/**
  * Remote non-agg object
  */
case class SMGRemoteObject(
                            id: String,
                            interval: Int,
                            vars: List[Map[String, String]],
                            cdefVars: List[Map[String, String]],
                            graphVarsIndexes: Seq[Int],
                            title: String,
                            stack: Boolean,
                            rrdType: String,
                            rraDef: Option[SMGRraDef]
                          ) extends SMGObjectView {

  override val rrdFile: Option[String] = None
  override val refObj: Option[SMGObjectUpdate] = None
}

/**
  * local copy of a remote object (one with fetched .rrd file)
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
                            rrdType: String,
                            rraDef: Option[SMGRraDef]
                          ) extends SMGObjectView {

  def this(robj:SMGObjectView, rrdFile: String) = this(robj.id,
    robj.interval,
    robj.vars,
    robj.cdefVars,
    robj.graphVarsIndexes,
    robj.title,
    robj.stack,
    Some(rrdFile),
    robj.rrdType,
    robj.rraDef
  )

  override val refObj: Option[SMGObjectUpdate] = None
}


case class SMGRemoteAggObjectView(
                            id: String,
                            objs: Seq[SMGObjectView],
                            op: String,
                            groupBy: SMGAggGroupBy.Value,
                            vars: List[Map[String, String]],
                            cdefVars: List[Map[String, String]],
                            graphVarsIndexes: Seq[Int],
                            title: String
                          ) extends SMGAggObjectView {
  override val refObj: Option[SMGObjectUpdate] = None

  override lazy val rrdType: String = objs.map(_.rrdType).distinct.mkString(",")
}
