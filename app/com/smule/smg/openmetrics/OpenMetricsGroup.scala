package com.smule.smg.openmetrics

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi

import scala.collection.immutable.StringOps
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

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
}
