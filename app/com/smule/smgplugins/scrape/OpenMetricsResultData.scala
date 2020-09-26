package com.smule.smgplugins.scrape

import com.smule.smg.openmetrics.OpenMetricsStat

case class OpenMetricsResultData(stats: Seq[OpenMetricsStat]) {
  val byUid: Map[String, OpenMetricsStat] = stats.groupBy(_.smgUid).map { t =>
    (t._1, t._2.head)
  }
}
