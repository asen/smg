package com.smule.smg.core

import com.smule.smg.rrd.SMGRrdUpdateData

trait CommandResult {
  val data: Object
  val asStr: String // to be used when passed to stdin
  // this can throw at runtime unless proper type
  def asUpdateData(limit: Int): SMGRrdUpdateData // threat the result as data points + optional TS to record
}
