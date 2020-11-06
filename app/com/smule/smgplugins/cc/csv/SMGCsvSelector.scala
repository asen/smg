package com.smule.smgplugins.cc.csv

trait SMGCsvSelector {
  val colName: String
  val colIndex: Option[Int]
  def idx(headers: Map[String,Int]): Int = if (colIndex.isDefined) colIndex.get else headers.getOrElse(colName, -1)
}

object SMGCsvSelector {
  val QUOTES = Set('\'', '"')

  def quotedVal(inp:String): Option[(String, String)] = {
    var rem = inp
    if (rem.nonEmpty && QUOTES.contains(rem(0))){
      val q = rem(0)
      rem = rem.drop(1)
      val eix = rem.indexOf(q)
      if (eix < 0)
        return None
      val ret = rem.splitAt(eix)
      rem = ret._2.drop(1).stripLeading()
      Some(ret._1, rem)
    } else None
  }
}
