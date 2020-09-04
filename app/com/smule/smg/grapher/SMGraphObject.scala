package com.smule.smg.grapher

import com.smule.smg.core.{SMGObjectUpdate, SMGObjectView}
import com.smule.smg.rrd.SMGRraDef

/**
  * A Graph object derived from an update object (like SmgRrdObject).
  */
case class SMGraphObject(
                          id: String,
                          interval: Int,
                          vars: List[Map[String, String]],
                          cdefVars: List[Map[String, String]],
                          title: String,
                          stack: Boolean,
                          gvIxes: List[Int],
                          rrdFile: Option[String],
                          refObj: Option[SMGObjectUpdate],
                          rrdType: String,
                          labels: Map[String,String]
                        ) extends SMGObjectView {

  override val graphVarsIndexes: Seq[Int] = if (gvIxes.isEmpty) refObj.map(_.vars.indices).getOrElse(vars.indices) else gvIxes

  override val parentIds: Seq[String] = refObj.map(_.parentIds).getOrElse(Seq())

  override val rraDef: Option[SMGRraDef] = refObj.flatMap(_.rraDef)
}
