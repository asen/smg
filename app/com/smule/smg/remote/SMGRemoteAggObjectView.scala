package com.smule.smg.remote

import com.smule.smg.core.{SMGAggGroupBy, SMGObjectUpdate, SMGObjectView}
import com.smule.smg.grapher.SMGAggObjectView

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

