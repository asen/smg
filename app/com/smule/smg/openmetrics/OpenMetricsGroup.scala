package com.smule.smg.openmetrics

case class OpenMetricsGroup(
                             metaKey: Option[String],
                             metaType: Option[String],
                             metaHelp: Option[String],
                             rows: Seq[OpenMetricsRow]
                           ) {
  val isEmpty: Boolean = rows.isEmpty

  def title(prefix: String, name: String, groupIndex: Option[Int]): String = {
    val grpIdxTitlePart = if (groupIndex.isDefined) { s" (${groupIndex.get})" } else ""
    (if (prefix == "") "" else s"($prefix) ") +
      name + grpIdxTitlePart + metaHelp.map(s => s" - ${s}").getOrElse("")
  }

  lazy val sortKey: String = metaKey.getOrElse(rows.headOption.map(_.name).getOrElse(""))
}
