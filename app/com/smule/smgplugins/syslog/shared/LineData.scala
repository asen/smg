package com.smule.smgplugins.syslog.shared

case class LineData(tsms: Long, objectIdParts: List[String], values: List[Double])  {
  lazy val objectId: String = objectIdParts.mkString(".")
}
