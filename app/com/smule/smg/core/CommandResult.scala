package com.smule.smg.core

trait CommandResult {
  val data: Object
  val asStr: String // to be used when passed to stdin
  // this can throw at runtime unless proper type
  def asDoubleList(limit: Int): List[Double] // threat the result as data points to record
}
