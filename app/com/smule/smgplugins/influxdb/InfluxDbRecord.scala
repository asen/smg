package com.smule.smgplugins.influxdb

case class InfluxDbRecord(
                         uid: String,
                         tags: Seq[(String,String)],
                         value: Double,
                         ts: Long
                         ) {

  private lazy val tagsStr = if (tags.isEmpty) "" else {
    "," + tags.sortBy(_._1).map { case (k,v) =>
      s"$k=${v.replaceAll("[\\s,]", "_")}"
    }.mkString(",")
  }

  lazy val line: String = s"${uid}${tagsStr} value=$value $ts"
}
