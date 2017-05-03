package com.smule.smg

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
  * A SMG aggregate object view - representing a set of SMGObjects (rrd dbs) having identical
  * variables and subject to aggregation.
  *
  * Note that this is different from SMGRrdAggObject which is a single rrd file updated with values
  * coming from multiple other update objects.
  *
  */
trait SMGAggObjectView extends SMGObjectView {

  val objs: Seq[SMGObjectView]
  val op: String

  override val stack: Boolean = (op == "STACK") || objs.head.stack

  /**
    * @inheritdoc
    */
  override def showUrl: String = "/showAgg?op=" + op + "&ids=" + objs.map(_.id).mkString(",")

  override def dashUrl: String = {
    val rx = s"^(${objs.map(obj => SMGRemote.localId(obj.id)).distinct.mkString("|")})$$"
    "/dash?rx=" + java.net.URLEncoder.encode(rx, "UTF-8") + "&agg=" + op + "&remote=" + SMGRemote.remoteId(id)
  }

  override def parentDashUrl: Option[String] = None

  override def fetchUrl(period: String): String = "/fetchAgg?s=" + period + "&op=" + op + "&ids=" + objs.map(_.id).mkString(",")

  override val rrdFile = None
  override val isAgg = true

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
      ( t._1, SMGAggObjectView.build(t._2, op) )
    }
  }

  override def interval: Int = objs.map(_.interval).min
}

/**
  * Use SMGLocalAggObjectView.build() to build from multiple compatible objects and
  * with generated id, title and vars
  *
  */
case class SMGLocalAggObjectView(id: String,
                                 objs: Seq[SMGObjectView],
                                 op: String,
                                 vars : List[Map[String, String]],
                                 cdefVars: List[Map[String, String]],
                                 graphVarsIndexes: Seq[Int],
                                 title: String ) extends SMGAggObjectView {
  override val refObj: Option[SMGObjectUpdate] = None

  override lazy val rrdType: String = objs.map(_.rrdType).distinct.mkString(",")
}

object SMGAggGroupBy extends Enumeration {

  val GB_VARS, GB_VARSSX, GB_LBLMU, GB_LBLMUSX = Value

  def gbDesc(v: Value): String = v match {
    case GB_VARS => "By same vars defs (default)"
    case GB_VARSSX => "By same vars defs and object id suffix"
    case GB_LBLMU => "By same vars labels and units"
    case GB_LBLMUSX => "By same vars labels and units and object id suffix"
    case x => throw new RuntimeException(s"The impossible has happened: $x")
  }

  def gbVal(s: String): Option[Value] = Try(withName(s)).toOption

  def defaultGroupBy: Value = GB_VARS

  private def varsDesc(vars: List[Map[String,String]]) = s"Vars (${vars.size}): " + vars.zipWithIndex.map { case (v, ix) =>
    "(" + v.map { case (k,s) =>
      s"$k=$s"
    }.mkString(" ") + ")"
  }.mkString(", ")


  private def objectGroupByVars(ov: SMGObjectView, gb: Value) = {
    gb match {
      case GB_VARS => ov.filteredVars(true)
      case GB_VARSSX => {
        ov.filteredVars(true).map { m =>
          m ++ Map("sx" -> ov.id.split('.').last)
        }
      }
      case GB_LBLMU => ov.filteredVars(true).map { m =>
        m.filter { case (k, v) =>
          (k == "label") || (k == "mu")
        }
      }
      case GB_LBLMUSX => ov.filteredVars(true).map { m =>
        m.filter { case (k, v) =>
          (k == "label") || (k == "mu")
        } ++ Map("sx" -> ov.id.split('.').last)
      }
    }
  }

  def groupByVars(lst: Seq[SMGObjectView], gb: Value): Seq[(String, Seq[SMGObjectView])] = {
    // group by vars trying to preserve ordering
    val grouped = mutable.Map[List[Map[String,String]],Seq[SMGObjectView]](lst.groupBy(ov => objectGroupByVars(ov,gb)).toSeq:_*)
    val ret = ListBuffer[(String, Seq[SMGObjectView])]()
    lst.foreach { ov =>
      val gbv = objectGroupByVars(ov, gb)
      val elem = grouped.remove(gbv)
      if (elem.isDefined) {
        ret += Tuple2(varsDesc(gbv), elem.get)
      }
    }
    ret.toList
  }
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
    * Build a SMGLocalAggObjectView from provided sequence of SMGObjects and aggregate operation.
    * Objects must have identical vars
    *
    * @param objs - sequence of SMGObjects to build the aggregate from
    * @param op - aggregate operation
    * @param title - optional title. If None, a title will be generated from the object titles
    * @return - the newly created SMGLocalAggObjectView
    */
  def build(objs: Seq[SMGObjectView], op:String, title: Option[String] = None): SMGLocalAggObjectView = {
    val myTitle = if (title.isDefined) title.get else "(" + op + ", " + objs.size + " objects) " +
       buildTitle(objs.map(o => o.title))
    SMGLocalAggObjectView(id = myGenId(objs, objs.head.vars, objs.head.cdefVars, objs.head.graphVarsIndexes, op),
      objs = objs,
      op = op,
      vars = objs.head.vars,
      cdefVars = objs.head.cdefVars,
      graphVarsIndexes = objs.head.graphVarsIndexes,
      title = myTitle)
  }


}
