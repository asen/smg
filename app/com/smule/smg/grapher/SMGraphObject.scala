package com.smule.smg.grapher

import com.smule.smg.core.{SMGObjectUpdate, SMGObjectVar, SMGObjectView}
import com.smule.smg.rrd.SMGRraDef

/**
  * A Graph object derived from an update object (like SmgRrdObject).
  */
case class SMGraphObject(
                          id: String,
                          interval: Int,
                          vars: List[SMGObjectVar],
                          cdefVars: List[SMGObjectVar],
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

  override val searchText: String = super.searchText + " " + refObj.map(_.searchText).getOrElse("")
}
