package com.smule.smg.rrd

case class SMGRrdUpdateData(values: List[Double], ts: Option[Int]) {
  def withTss(newTss: Option[Int]): SMGRrdUpdateData = if (ts == newTss) this else {
    SMGRrdUpdateData(values, newTss)
  }
}
