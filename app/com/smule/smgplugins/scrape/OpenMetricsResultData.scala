package com.smule.smgplugins.scrape

import com.smule.smg.openmetrics.{OpenMetricsGroup, OpenMetricsRow}

case class OpenMetricsResultData(stats: Seq[OpenMetricsGroup]) {
  val byUid: Map[String, OpenMetricsRow] = stats.flatMap(_.rows).groupBy(_.labelUid).map { t =>
    (t._1, t._2.head)
  }
}
