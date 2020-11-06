package com.smule.smgplugins.cc.csv

import scala.collection.mutable.ListBuffer
import scala.util.Try

case class SMGCsvSelectorRow(
                              colName: String,
                              colIndex: Option[Int],
                              colValue: String,
                              isNegative: Boolean,
                              isRegex: Boolean
                            ) extends SMGCsvSelector  {
  def matches(row: Array[String], headers: Map[String, Int]): Boolean = {
    val ix = idx(headers)
    val valueOpt = row.lift(ix)
    val ret = valueOpt.exists { vstr =>
      if (isRegex)
        vstr.matches(colValue)
      else
        vstr == colValue
    }
    if (isNegative)
      !ret
    else
      ret
  }
}


object SMGCsvSelectorRow {
//  private val log = SMGLogger

  private def quotedVal(inp: String): Option[(String, String)] = SMGCsvSelector.quotedVal(inp)

  def parseOne(inp: String): (Option[SMGCsvSelectorRow], String) = {
    lazy val emptyRet = (None, inp)
    if (inp.isBlank)
      return emptyRet
    var rem = inp.stripLeading()
    val quotedKeyT = quotedVal(inp)
    val key = if (quotedKeyT.isDefined){
      rem = quotedKeyT.get._2.stripLeading()
      if (!rem.startsWith("="))
        return emptyRet
      rem = rem.drop(1).stripLeading()
      quotedKeyT.get._1
    } else {
      val eix = rem.indexOf('=')
      if (eix < 0)
        return emptyRet
      val ret = rem.splitAt(eix)
      rem = ret._2.drop(1).stripLeading()
      ret._1.stripTrailing()
    }
    var isNegative: Boolean = false
    if (rem.startsWith("!")){
      isNegative = true
      rem = rem.drop(1)
    }
    var isRegex: Boolean = false
    if (rem.startsWith("~")){
      isRegex = true
      rem = rem.drop(1)
    }
    rem = rem.stripLeading()
    val valT = quotedVal(rem)
    val value = if (valT.isDefined){
      rem = valT.get._2
      valT.get._1
    } else {
      // space separated val
      val arr = rem.split("\\s+", 2)
      rem = arr.lift(1).getOrElse("")
      arr(0)
    }
    val ret = SMGCsvSelectorRow(
      colName = key,
      colIndex = if (quotedKeyT.isDefined) None else Try(key.toInt).toOption,
      colValue = value,
      isNegative = isNegative,
      isRegex = isRegex)
    (Some(ret), rem)
  }

  def parseAll(inp: String): (List[SMGCsvSelectorRow], String) = {
    val ret = ListBuffer[SMGCsvSelectorRow]()
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
    (ret.toList, rem)
  }
}
