package com.smule.smg.remote

import com.smule.smg.core.{SMGAggGroupBy, SMGObjectUpdate, SMGObjectVar, SMGObjectView}
import com.smule.smg.grapher.SMGAggObjectView

case class SMGRemoteAggObjectView(
                                   id: String,
                                   objs: Seq[SMGObjectView],
                                   op: String,
                                   groupBy: SMGAggGroupBy.Value,
                                   gbParam: Option[String],
                                   vars: List[SMGObjectVar],
                                   cdefVars: List[SMGObjectVar],
                                   graphVarsIndexes: Seq[Int],
                                   title: String,
                                   searchTextOpt: Option[String]
                                 ) extends SMGAggObjectView {
  override val refObj: Option[SMGObjectUpdate] = None
  override val parentIds: Seq[String] = objs.flatMap { ou => ou.parentIds }.distinct
  override lazy val rrdType: String = objs.map(_.rrdType).distinct.mkString(",")
  override def searchText: String = if (searchTextOpt.isDefined) searchTextOpt.get else super.searchText
}

