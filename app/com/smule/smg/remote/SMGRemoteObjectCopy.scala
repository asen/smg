package com.smule.smg.remote

import com.smule.smg.core.{SMGObjectUpdate, SMGObjectView}
import com.smule.smg.rrd.SMGRraDef

/**
  * local copy of a remote object (one with fetched .rrd file)
  */
case class SMGRemoteObjectCopy(
                                id: String,
                                parentIds: Seq[String],
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
    robj.parentIds,
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


