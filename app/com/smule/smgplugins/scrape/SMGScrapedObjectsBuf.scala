package com.smule.smgplugins.scrape

import com.smule.smg.config.SMGConfIndex
import com.smule.smg.core.{SMGPreFetchCmd, SMGRrdAggObject, SMGRrdObject}
import com.smule.smg.grapher.SMGraphObject

import scala.collection.mutable.ListBuffer

case class SMGScrapedObjectsBuf() {
  val preFetches: ListBuffer[SMGPreFetchCmd] = ListBuffer[SMGPreFetchCmd]()
  val rrdObjects: ListBuffer[SMGRrdObject] = ListBuffer[SMGRrdObject]()
  val viewObjects: ListBuffer[SMGraphObject] = ListBuffer[SMGraphObject]()
  val aggObjects: ListBuffer[SMGRrdAggObject] = ListBuffer[SMGRrdAggObject]()
  val indexes: ListBuffer[SMGConfIndex] = ListBuffer[SMGConfIndex]()

  def isEmpty: Boolean = rrdObjects.isEmpty && aggObjects.isEmpty && viewObjects.isEmpty

  def mergeOther(other: SMGScrapedObjectsBuf): Unit = {
    preFetches ++= other.preFetches
    rrdObjects ++= other.rrdObjects
    viewObjects ++= other.viewObjects
    aggObjects ++= other.aggObjects
    indexes ++= other.indexes
  }

  // sort indexes by id, in-place
  def sortIndexes(): Unit = {
    val sorted = indexes.sortBy(_.id).toList
    indexes.clear()
    indexes ++= sorted
  }
}
