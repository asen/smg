package com.smule.smg.cdash

case class CDashboardConfig(id: String, title: Option[String], refresh: Int, items: Seq[CDashConfigItem]) {
  lazy val titleStr: String = title.getOrElse(id)
}
