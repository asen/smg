package com.smule.smg.core

import com.smule.smg.rrd.SMGRrdUpdateData

// the "normal" output from a bash command
case class CommandResultListString(lst: List[String], tss: Option[Int]) extends CommandResult {
  override val data: Object = lst
  override val asStr: String = lst.mkString("\n")
  def asUpdateData(limit: Int): SMGRrdUpdateData = {
    // XXX this does not work OK with mutltiple values and diff timestamps,
    // timestamps will be ignored if more than one and different
    var conflictingTsms = false
    var tsms: Option[Long] = None
    val myLst = if (limit <= 0) lst else lst.take(limit)
    val doubles = myLst.map{ s =>
      val arr = s.stripLeading().split("\\s+")
      val newTsms = arr.lift(1).map(_.toLong)
      if (tsms.isEmpty && !conflictingTsms)
        tsms = newTsms
      else if (tsms != newTsms){
        conflictingTsms = true
        tsms = None
      }
      arr(0).toDouble
    }
    val mytss = tsms.map(x => (x / 1000).toInt)
    SMGRrdUpdateData(doubles, if (mytss.isDefined) mytss else tss)
  }
}

