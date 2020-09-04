package com.smule.smg.remote

import com.smule.smg.core.{SMGObjectUpdate, SMGObjectView, _}
import com.smule.smg.grapher.SMGAggObjectView
import com.smule.smg.rrd.SMGRraDef

/**
  * Created by asen on 12/4/15.
  */

/**
  * Remote non-agg object
  */
case class SMGRemoteObject(
                            id: String,
                            parentIds: Seq[String],
                            interval: Int,
                            vars: List[Map[String, String]],
                            cdefVars: List[Map[String, String]],
                            graphVarsIndexes: Seq[Int],
                            title: String,
                            stack: Boolean,
                            rrdType: String,
                            rraDef: Option[SMGRraDef],
                            labels: Map[String,String]
                          ) extends SMGObjectView {

  override val rrdFile: Option[String] = None
  override val refObj: Option[SMGObjectUpdate] = None
}
