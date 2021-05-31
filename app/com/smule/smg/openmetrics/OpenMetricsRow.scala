package com.smule.smg.openmetrics

case class OpenMetricsRow(
                           name: String,
                           labels: Seq[(String, String)],
                           value: Double,
                           tsms: Option[Long]
                         ) {

  lazy val labelUid: String = OpenMetricsParser.labelUid(name, labels)
  def indexUid(idx: Int): String = OpenMetricsParser.groupIndexUid(name, Some(idx))
  lazy val labelsAsMap: Map[String, String] = labels.toMap
}
