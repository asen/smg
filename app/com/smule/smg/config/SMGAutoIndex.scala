package com.smule.smg.config

import com.smule.smg.core.{SMGAggGroupBy, SMGFilter, SMGIndex}
import com.smule.smg.remote.SMGRemote

import scala.collection.mutable.ListBuffer
/**
  * Created by asen on 11/17/15.
  */

/**
  * A class representing an automatically discovered index (from object ids)
  * @param id - this index id (maps to filter prefix)
  * @param children - list of dicovered child indexes
  * @param remoteId - an optional remoe id for this auto index (None if local)
  * @param parentId - an optional parent auto index id. None means top-level
  */
case class SMGAutoIndex(id: String, children: Seq[SMGAutoIndex], remoteId: Option[String], parentId: Option[String]) extends SMGIndex {

  // TODO make these configurable

  override val cols: Option[Int] = None

  override val rows: Option[Int] = None

  override val title: String = id

  override val flt: SMGFilter = SMGFilter.fromPrefixWithRemote(SMGRemote.localId(id),
    if (remoteId.isEmpty) Seq(SMGRemote.local.id) else Seq(remoteId.get))

  override val childIds: Seq[String] = children.map(c => c.id)

  override def asUrl: String = flt.asUrlForPage(0, cols, rows)

  override def asUrlForPeriod(aPeriod: String): String = flt.asUrlForPage(0, cols, rows, Some(aPeriod))

  override val aggOp: Option[String] = None
  override val xRemoteAgg = false
  override val aggGroupBy: Option[SMGAggGroupBy.Value] = None
  override val gbParam: Option[String] = None

  override val period: Option[String] = None
  override val desc: Option[String] = None

  override val disableHeatmap: Boolean = false // TODO ???

  def findChildIdx(cid: String): Option[SMGAutoIndex] = {
    if (cid == id) return Some(this)
    if (children.isEmpty) return None
    for (c <- children) {
      val cix = c.findChildIdx(cid)
      if (cix.isDefined) return cix
    }
    None
  }
}

/**
  * A singleton providing helpers to generate automatic indexes
  */
object SMGAutoIndex {

//  val log = SMGLogger

  private def objId2PrefixList(suffix: String, prefix: String = "") : List[String] = {
    val comps = suffix.split("\\.", 2)
    val newPrefix = if (prefix == "") comps(0) else prefix + "." + comps(0)
    if (comps.length > 1) {
      newPrefix :: objId2PrefixList(comps(1), newPrefix)
    } else {
      List(newPrefix)
    }
  }


  private def getLcp(x: List[String], y:List[String]): List[String] = {
    if (x.isEmpty || y.isEmpty|| (x.head != y.head)) {
      List()
    } else {
      x.head :: getLcp(x.tail, y.tail)
    }
  }

  def getAutoIndex(objIds: Seq[String], indexPx: String = "", remoteId: Option[String], needSorting: Boolean = true): Seq[SMGAutoIndex] = {
    val parentId = if (indexPx == "") None else Some(indexPx.replaceAll("\\.$",""))
    if (objIds.isEmpty) {
      return List[SMGAutoIndex]()
    }
    if (objIds.tail.isEmpty) { //  size < 2
      return List[SMGAutoIndex](
          SMGAutoIndex(indexPx, List[SMGAutoIndex](), remoteId, parentId)
        )
    }
    val oids = if (needSorting) objIds.sorted else objIds
    val pxlsts = for (oid <- oids) yield objId2PrefixList(oid)
    var prevLcp = pxlsts.head
    val curList = ListBuffer[String](oids.head)
    val ret = ListBuffer[SMGAutoIndex]()
    for (pxlst <- pxlsts.tail) {
      val newLcp = getLcp(prevLcp, pxlst)
      if (newLcp.isEmpty || (newLcp.last == indexPx)) {
        if (curList.size > 1) {
          ret += SMGAutoIndex(prevLcp.last, getAutoIndex(curList.toList, prevLcp.last, remoteId, needSorting = false), remoteId, parentId)
        } else {
          ret += SMGAutoIndex(prevLcp.last, List[SMGAutoIndex](), remoteId, parentId)
        }
        curList.clear()
        prevLcp = pxlst
      } else {
        prevLcp = newLcp
      }
      curList += pxlst.last
    }
    if (curList.size > 1) {
      ret += SMGAutoIndex(prevLcp.last, getAutoIndex(curList.toList, prevLcp.last, remoteId, needSorting = false), remoteId, parentId)
    } else {
      ret += SMGAutoIndex(prevLcp.last, List[SMGAutoIndex](), remoteId, parentId)
    }

    ret.toList
  }
}


