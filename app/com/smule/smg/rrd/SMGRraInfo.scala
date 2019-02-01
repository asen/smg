package com.smule.smg.rrd

case class SMGRraInfo(cf: String, pdpPerRow: Int, rows: Int) {
  def step(interval: Int): Int = interval * pdpPerRow
  def maxPeriod(interval: Int): Int = step(interval) * rows
  lazy val toS = s"$cf:$pdpPerRow:$rows"
  lazy val sortTuple: (String, Int, Int) = (cf, pdpPerRow * rows, pdpPerRow)
}

