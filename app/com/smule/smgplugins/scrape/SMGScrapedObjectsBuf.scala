package com.smule.smgplugins.scrape

import com.smule.smg.config.SMGConfIndex
import com.smule.smg.core.{SMGObjectBase, SMGPreFetchCmd, SMGRrdAggObject, SMGRrdObject}
import com.smule.smg.grapher.SMGraphObject

import scala.collection.mutable.ListBuffer

case class SMGScrapedObjectsBuf() {
  val preFetches: ListBuffer[SMGPreFetchCmd] = ListBuffer[SMGPreFetchCmd]()
  val objects: ListBuffer[SMGObjectBase] = ListBuffer[SMGObjectBase]()
  val indexes: ListBuffer[SMGConfIndex] = ListBuffer[SMGConfIndex]()

  def isEmpty: Boolean = objects.isEmpty

  def mergeOther(other: SMGScrapedObjectsBuf): Unit = {
    preFetches ++= other.preFetches
    objects ++= other.objects
    indexes ++= other.indexes
  }

  // sort indexes by id, in-place
  def sortIndexes(): Unit = {
    val sorted = indexes.sortBy(_.id).toList
    indexes.clear()
    indexes ++= sorted
  }
}
