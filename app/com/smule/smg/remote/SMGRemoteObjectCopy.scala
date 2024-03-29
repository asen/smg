package com.smule.smg.remote

import com.smule.smg.core.{SMGObjectUpdate, SMGObjectVar, SMGObjectView}
import com.smule.smg.rrd.SMGRraDef

/**
  * local copy of a remote object (one with fetched .rrd file)
  */
case class SMGRemoteObjectCopy(
                                id: String,
                                parentIds: Seq[String],
                                interval: Int,
                                vars: List[SMGObjectVar],
                                cdefVars: List[SMGObjectVar],
                                graphVarsIndexes: Seq[Int],
                                title: String,
                                stack: Boolean,
                                rrdFile: Option[String],
                                rrdType: String,
                                rraDef: Option[SMGRraDef],
                                labels: Map[String,String]
                              ) extends SMGObjectView {

  def this(robj:SMGObjectView, rrdFile: String) = this(robj.id,
    robj.parentIds,
    robj.interval,
    robj.vars,
    robj.cdefVars,
    robj.graphVarsIndexes,
    robj.title,
    robj.stack,
    Some(rrdFile),
    robj.rrdType,
    robj.rraDef,
    robj.labels
  )

  override val refObj: Option[SMGObjectUpdate] = None
}


