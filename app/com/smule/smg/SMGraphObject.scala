package com.smule.smg

/**
  * A Graph object derived from an update obkect (like SmgRrdObject).
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
                          rrdType: String
                        ) extends SMGObjectView {

  override val graphVarsIndexes: Seq[Int] = if (gvIxes.isEmpty) refObj.map(_.vars.indices).getOrElse(vars.indices) else gvIxes
}
