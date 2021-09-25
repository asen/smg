package com.smule.smg.grapher

import com.smule.smg.core.{SMGAggGroupBy, SMGObjectUpdate, SMGObjectVar, SMGObjectView}

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
                                 vars : List[SMGObjectVar],
                                 cdefVars: List[SMGObjectVar],
                                 graphVarsIndexes: Seq[Int],
                                 title: String ) extends SMGAggObjectView {

  override val refObj: Option[SMGObjectUpdate] = None
  override val parentIds: Seq[String] = Seq() // TODO??
  override lazy val rrdType: String = objs.map(_.rrdType).distinct.mkString(",")
}


