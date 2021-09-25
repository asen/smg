package com.smule.smg.grapher

import com.smule.smg.config.SMGStringUtils
import com.smule.smg.core.{SMGAggGroupBy, SMGObjectVar, SMGObjectView}
import com.smule.smg.rrd.SMGRraDef
import com.smule.smg.remote.SMGRemote

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * A SMG aggregate object view - representing a set of SMGObjects (rrd dbs) having compatible
  * variables (grouped according to groupBy rules) and subject to aggregation.
  *
  * Note that this is different from SMGRrdAggObject which is a single rrd file updated with values
  * coming from multiple other update objects.
  *
  */
trait SMGAggObjectView extends SMGObjectView {

  val objs: Seq[SMGObjectView]
  val op: String
  val groupBy: SMGAggGroupBy.Value
  val gbParam: Option[String]

  def groupByKey: SMGAggGroupBy = SMGAggGroupBy.objectGroupByVars(objs.head, groupBy, gbParam)

  override val stack: Boolean = (op == "STACK") || objs.head.stack

  // merge values with the same key with a comma
  override val labels: Map[String, String] = SMGAggObjectView.mergeLabels(objs.map(_.labels))

  /**
    * @inheritdoc
    */
  override def showUrl(addParams: Map[String, String] = Map()): String = "/showAgg?op=" + op +
    "&ids=" + objs.map(_.id).mkString(",") +
    (if (groupBy != SMGAggGroupBy.defaultGroupBy) s"&gb=${groupBy.toString}" else "") + addParamsToStr(addParams, "&")

  override def dashUrl: String = {
    val rmtId = SMGRemote.remoteId(id)
    val rx = s"^(${objs.map(obj => SMGRemote.localId(obj.id)).distinct.mkString("|")})$$"
    "/dash?rx=" + java.net.URLEncoder.encode(rx, "UTF-8") + "&agg=" + op +
      (if (rmtId != SMGRemote.local.id) "&remote=" + java.net.URLEncoder.encode(rmtId, "UTF-8") else "") +
      (if (groupBy != SMGAggGroupBy.defaultGroupBy) s"&gb=${groupBy.toString}" else "")
  }

  override def parentDashUrl: Option[String] = None

  override def fetchUrl(period: String, gopts: GraphOptions): String = "/fetchAgg?s=" + period + "&op=" + op +
    "&ids=" + objs.map(_.id).mkString(",") +
    (if (groupBy != SMGAggGroupBy.defaultGroupBy) s"&gb=${groupBy.toString}" else "") +
    (if (gopts.step.isDefined) s"&r=${gopts.step.get}" else "") +
    (if (gopts.pl.isDefined) s"&e=${gopts.pl.get}" else "")

  override val rrdFile: Option[String] = None
  override val isAgg: Boolean = true
  override val rraDef: Option[SMGRraDef] = objs.maxBy(_.interval).rraDef

  val isCrossRemoteAgg: Boolean = if (objs.isEmpty)
    false
  else
    objs.tail.exists(ov => SMGRemote.remoteId(ov.id) != SMGRemote.remoteId(objs.head.id))

  /**
    * Construct a map of aggregate objects from this one, segregating the contained objects by remote
    *
    * @return - a map of remoteId -> SMGAggObjectView
    */
  def splitByRemoteId: Map[String,SMGAggObjectView] = {
    objs.groupBy( o => SMGRemote.remoteId(o.id)).map { t =>
      ( t._1, SMGAggObjectView.build(t._2, op, groupBy, gbParam, None) )
    }
  }

  override def interval: Int = objs.map(_.interval).min
}

/**
  * Singleton defining helpers to build aggregate objects (SMGAggobject)
  */
object SMGAggObjectView {

  private def myGenId(objs: Seq[SMGObjectView],
                      vars: List[SMGObjectVar],
                      cdefVars: List[SMGObjectVar],
                      graphVarsIndexes: Seq[Int],
                      op: String): String = {
    val byRemote = objs.groupBy(ov => SMGRemote.remoteId(ov.id))
    val remotePx = if (byRemote.keys.size != 1) {
      // cross-remote or empty objs slist
      ""
    } else { //all objects are from the same remote
      if (SMGRemote.isRemoteObj(objs.head.id))
        SMGRemote.prefixedId(SMGRemote.remoteId(objs.head.id), "")
      else ""
    }

    val md = java.security.MessageDigest.getInstance("SHA-1")
    for (o <- objs.sortBy(_.id) ) {
      md.update(SMGRemote.localId(o.id).getBytes())
    }
    for (m <- vars) {
      for (k <- m.m.keys.toSeq.sorted) {
        md.update(k.getBytes())
        md.update(m.m(k).getBytes())
      }
    }
    for (vi <- graphVarsIndexes.toList.sorted) md.update(vi.toString.getBytes)
    for (m <- cdefVars) {
      for (k <- m.m.keys.toSeq.sorted) {
        md.update(k.getBytes())
        md.update(m.m(k).getBytes())
      }
    }
    remotePx + md.digest().map("%02x".format(_)).mkString + "-" + op
  }

  /**
    * A helper to strip common for all in the list prefix and suffix from each string in the list
    * Useful to get unique object title portions to combine in a single object title.
    * @param sep - prefix/suffix separator
    * @param lst - list of strings to strip
    * @return - a new list containing stripped versions of the input strings
    */
  def stripCommonStuff(sep: Char, lst: Seq[String]): Seq[String] = {
    val cp = SMGStringUtils.commonListPrefix(sep, lst)
    val strippedPrefixes = lst.map(s => s.substring(cp.length))
    val cs = SMGStringUtils.commonListSuffix(sep, strippedPrefixes)
    strippedPrefixes.map(s => s.substring(0, s.length - cs.length)).map {
      s => if (s.nonEmpty) {
        var six = if (s.charAt(0) == sep) 1 else 0
        val eix = if (s.charAt(s.length - 1) == sep) s.length - 1 else s.length
        if (six < eix) s.substring(six, eix) else s
      } else s
    }
  }

  private def buildTitle(lst: Seq[String]) : String = {
    val cp = SMGStringUtils.commonListPrefix(' ', lst)
    val cs = SMGStringUtils.commonListSuffix(' ', lst)
    val uncommon = stripCommonStuff(' ', lst)
    (if (cp == "") "" else cp + " ") +
      uncommon.mkString(", ") +
      (if (cs == "") "" else " " + cs)
  }

  /**
    * Build a SMGAggObjectViewLocal from provided sequence of SMGObjects and aggregate operation.
    * Objects must have identical vars
    *
    * @param objs - sequence of SMGObjects to build the aggregate from
    * @param op - aggregate operation
    * @param title - optional title. If None, a title will be generated from the object titles
    * @return - the newly created SMGAggObjectViewLocal
    */
  def build(objs: Seq[SMGObjectView], op:String,
            groupBy: SMGAggGroupBy.Value, gbParam: Option[String],
            title: Option[String]): SMGAggObjectViewLocal = {
    val myTitle = if (title.isDefined) title.get else "(" + op + ", " + objs.size + " objects) " +
       buildTitle(objs.map(o => o.title))
    SMGAggObjectViewLocal(id = myGenId(objs, objs.head.vars, objs.head.cdefVars, objs.head.graphVarsIndexes, op),
      objs = objs,
      op = op,
      groupBy = groupBy,
      gbParam = gbParam,
      vars = objs.head.vars,
      cdefVars = objs.head.cdefVars,
      graphVarsIndexes = objs.head.graphVarsIndexes,
      title = myTitle)
  }

  def mergeLabels(inp: Seq[Map[String, String]]): Map[String, String] =
    inp.foldLeft(mutable.Map[String, ListBuffer[String]]()) { case (mm, sm) =>
      sm.foreach { case (k,v) =>
        if (!mm.contains(k)){
          mm(k) = ListBuffer()
        }
        mm(k) += v
      }
      mm
    }.map { case (k, lb) =>
      (k, lb.sorted.distinct.mkString(","))
    }.toMap
}
