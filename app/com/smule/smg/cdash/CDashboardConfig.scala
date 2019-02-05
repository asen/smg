package com.smule.smg.cdash

case class CDashboardConfig(id: String, title: Option[String], items: Seq[CDashConfigItem]) {
  lazy val titleStr: String = title.getOrElse(id)

  val refreshInterval = 60 // TODO make configurable
}
