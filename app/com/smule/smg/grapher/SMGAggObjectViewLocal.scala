package com.smule.smg.grapher

import com.smule.smg.core.{SMGAggGroupBy, SMGObjectUpdate, SMGObjectView}

/**
  * Use SMGAggObjectView.build() to build from multiple compatible objects and
  * with generated id, title and vars
  *
  */
case class SMGAggObjectViewLocal(id: String,
                                 objs: Seq[SMGObjectView],
                                 op: String,
                                 groupBy: SMGAggGroupBy.Value,
                                 gbParam: Option[String],
                                 vars : List[Map[String, String]],
                                 cdefVars: List[Map[String, String]],
                                 graphVarsIndexes: Seq[Int],
                                 title: String ) extends SMGAggObjectView {

  override val refObj: Option[SMGObjectUpdate] = None
  override val parentIds: Seq[String] = Seq() // TODO??
  override lazy val rrdType: String = objs.map(_.rrdType).distinct.mkString(",")
}


