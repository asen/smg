package com.smule.smg.grapher

import com.smule.smg.core.{SMGAggGroupBy, SMGObjectView}
import com.smule.smg.rrd.SMGRraDef
import com.smule.smg.remote.SMGRemote

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

  def groupByKey: SMGAggGroupBy = SMGAggGroupBy.objectGroupByVars(objs.head, groupBy)

  override val stack: Boolean = (op == "STACK") || objs.head.stack

  /**
    * @inheritdoc
    */
  override def showUrl: String = "/showAgg?op=" + op +
    "&ids=" + objs.map(_.id).mkString(",") +
    (if (groupBy != SMGAggGroupBy.defaultGroupBy) s"&gb=${groupBy.toString}" else "")

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
      ( t._1, SMGAggObjectView.build(t._2, op, groupBy) )
    }
  }

  override def interval: Int = objs.map(_.interval).min
}

/**
  * Singleton defining helpers to build aggregate objects (SMGAggobject)
  */
object SMGAggObjectView {

  private def myGenId(objs: Seq[SMGObjectView],
                      vars: List[Map[String, String]],
                      cdefVars: List[Map[String, String]],
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
      for (k <- m.keys.toSeq.sorted) {
        md.update(k.getBytes())
        md.update(m(k).getBytes())
      }
    }
    for (vi <- graphVarsIndexes.toList.sorted) md.update(vi.toString.getBytes)
    for (m <- cdefVars) {
      for (k <- m.keys.toSeq.sorted) {
        md.update(k.getBytes())
        md.update(m(k).getBytes())
      }
    }
    remotePx + md.digest().map("%02x".format(_)).mkString + "-" + op
  }

  private def getLcp(x: List[String], y:List[String]): List[String] = {
    if (x.isEmpty || y.isEmpty|| (x.head != y.head)) {
      List()
    } else {
      x.head :: getLcp(x.tail, y.tail)
    }
  }


  private def commonPrefix(sep: Char, s: String, t: String, out: String = ""): String = {
    val sl = s.split(sep)
    val tl = t.split(sep)
    getLcp(sl.toList, tl.toList).mkString(sep.toString)
  }

  private def commonListPrefix(sep: Char, lst: Seq[String], soFar: Option[String] = None): String = {
    if (lst.isEmpty) soFar.getOrElse("")
    else {
      val cp = commonPrefix(sep, soFar.getOrElse(lst.head), lst.head)
      commonListPrefix(sep, lst.tail, Some(cp))
    }
  }

  private def commonSuffix(sep: Char, s: String, t: String, out: String = ""): String = {
    commonPrefix(sep, s.reverse, t.reverse).reverse
  }

  private def commonListSuffix(sep: Char, lst: Seq[String], soFar: Option[String] = None): String = {
    if (lst.isEmpty) soFar.getOrElse("")
    else {
      val cp = commonSuffix(sep, soFar.getOrElse(lst.head), lst.head)
      commonListSuffix(sep, lst.tail, Some(cp))
    }
  }

  /**
    * A helper to strip common for all in the list prefix and suffix from each string in the list
    * Useful to get unique object title portions to combine in a single object title.
    * @param sep - prefix/suffix separator
    * @param lst - list of strings to strip
    * @return - a new list containing stripped versions of the input strings
    */
  def stripCommonStuff(sep: Char, lst: Seq[String]): Seq[String] = {
    val cp = commonListPrefix(sep, lst)
    val strippedPrefixes = lst.map(s => s.substring(cp.length))
    val cs = commonListSuffix(sep, strippedPrefixes)
    strippedPrefixes.map(s => s.substring(0, s.length - cs.length)).map {
      s => if (s.nonEmpty) {
        var six = if (s.charAt(0) == sep) 1 else 0
        val eix = if (s.charAt(s.length - 1) == sep) s.length - 1 else s.length
        if (six < eix) s.substring(six, eix) else s
      } else s
    }
  }

  private def buildTitle(lst: Seq[String]) : String = {
    val cp = commonListPrefix(' ', lst)
    val cs = commonListSuffix(' ', lst)
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
  def build(objs: Seq[SMGObjectView], op:String, groupBy: SMGAggGroupBy.Value, title: Option[String] = None): SMGAggObjectViewLocal = {
    val myTitle = if (title.isDefined) title.get else "(" + op + ", " + objs.size + " objects) " +
       buildTitle(objs.map(o => o.title))
    SMGAggObjectViewLocal(id = myGenId(objs, objs.head.vars, objs.head.cdefVars, objs.head.graphVarsIndexes, op),
      objs = objs,
      op = op,
      groupBy = groupBy,
      vars = objs.head.vars,
      cdefVars = objs.head.cdefVars,
      graphVarsIndexes = objs.head.graphVarsIndexes,
      title = myTitle)
  }


}