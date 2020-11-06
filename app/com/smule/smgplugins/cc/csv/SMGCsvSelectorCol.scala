package com.smule.smgplugins.cc.csv

import scala.collection.mutable.ListBuffer
import scala.util.Try

case class SMGCsvSelectorCol(
                              colName: String,
                              colIndex: Option[Int]
                            ) extends SMGCsvSelector

object SMGCsvSelectorCol {
  def parseOne(inp: String): (Option[SMGCsvSelectorCol], String) = {
    if (inp.isBlank)
      return (None, inp)
    var rem = inp.stripLeading()
    var quoted = false
    val qt = SMGCsvSelector.quotedVal(rem)
    val colNameIdx = if (qt.isDefined){
      quoted = true
      rem = qt.get._2
      qt.get._1
    } else {
      val arr = rem.split("\\s+", 2)
      rem = arr.lift(1).getOrElse("")
      arr(0)
    }
    (Some(SMGCsvSelectorCol(
      colName = colNameIdx,
      colIndex = if (quoted) None else Try(colNameIdx.toInt).toOption
    )), rem)
  }

  def parseAll(inp: String): List[SMGCsvSelectorCol] = {
    val ret = ListBuffer[SMGCsvSelectorCol]()
    var rem = inp.stripLeading()
    var break = false
    while (!break){
      val cur = parseOne(rem)
      if (cur._1.isEmpty)
        break = true
      else {
        ret += cur._1.get
        rem = cur._2
      }
    }
    ret.toList
  }
}
