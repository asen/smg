package com.smule.smgplugins.cc.csv

trait SMGCsvSelector {
  val colName: String
  val colIndex: Option[Int]
  def idx(headers: Map[String,Int]): Int = if (colIndex.isDefined) colIndex.get else headers.getOrElse(colName, -1)
}
