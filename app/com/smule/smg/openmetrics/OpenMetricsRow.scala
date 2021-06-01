package com.smule.smg.openmetrics

case class OpenMetricsRow(
                           name: String,
                           labels: Seq[(String, String)],
                           value: Double,
                           tsms: Option[Long]
                         ) {
  private var dupIndex: Option[Int] = None
  def setDupIndex(ix: Int): Unit = {
    if (dupIndex.isDefined)
      throw new RuntimeException("BUG: OpenMetricsRow.setDupIndex can be called only once, during parsing")
    dupIndex = Some(ix)
  }
  def labelUid: String = OpenMetricsParser.groupIndexUid(OpenMetricsParser.labelUid(name, labels), dupIndex)
  def indexUid(idx: Int): String = OpenMetricsParser.groupIndexUid(name, Some(idx))
  lazy val labelsAsMap: Map[String, String] = labels.toMap
}
