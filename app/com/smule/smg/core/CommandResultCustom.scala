package com.smule.smg.core

// output from a custom (plugin) command which is opaque to SMG and must be passed
// to another plugin command to extract data from it
case class CommandResultCustom(data: Object) extends CommandResult {
  override val asStr: String = data.toString

  override def asDoubleList(limit: Int): List[Double]  =
    throw new RuntimeException(s"CommandResultCustom($data) can not be treated as data points")
}

