package com.smule.smg.core

// the "normal" output from a bash command is a list of strings which SMG has to parse to numbers
// Plugin commands can return numbers directly if they were parsed in bulk in a parent command
// or if the custom fetch result is already numbers.
case class CommandResultListDouble(lst: List[Double]) extends CommandResult {
  override val data: Object = lst
  override val asStr: String = lst.mkString("\n")

  override def asDoubleList(limit: Int): List[Double] = lst.take(limit)
}
