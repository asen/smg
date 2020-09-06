package com.smule.smg.core

// the "normal" output from a bash command
case class CommandResultListString(lst: List[String]) extends CommandResult {
  override val data: Object = lst
  override val asStr: String = lst.mkString("\n")

  override def asDoubleList(limit: Int): List[Double] = lst.take(limit).map(_.toDouble)
}

