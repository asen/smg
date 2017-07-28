package com.smule.smg

/**
  * Graph.
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

  /**
    * The "show" url for this object
    * @return - a string representing an url to display this object details
    */
  override def showUrl: String = "/show/" + id

  override def fetchUrl(period: String, step: Option[Int]): String = "/fetch/" + id + "?s=" + period +
    "&r=" + step.map(_.toString).getOrElse("")

  override val isAgg: Boolean = false

  override val graphVarsIndexes: Seq[Int] = if (gvIxes.isEmpty) refObj.map(_.vars.indices).getOrElse(vars.indices) else gvIxes
}
