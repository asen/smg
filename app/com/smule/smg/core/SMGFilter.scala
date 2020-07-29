package com.smule.smg.core

import java.net.URLEncoder

import com.smule.smg.grapher.GraphOptions
import com.smule.smg.remote.SMGRemote
import com.smule.smg.rrd.SMGRrd

import scala.util.Try
import scala.util.matching.Regex

/**
  * Created by asen on 11/14/15.
  */

/**
  * Object encapsulating a SMG Filter - a search criteria to select a subset from all objects for display
  * @param px - optional object id prefix. if present, only object ids matching that prefix will be selected
  * @param sx - optional object id suffix. if present, only object ids matching that suffix will be selected
  * @param rx - optional object id regex. if present, only object ids matching that regex will be selected
  * @param rxx - optional object id regex to exclude. if present, only objects with ids NOT matching that regex will be selected
  * @param trx - optional title regex. if present, only objects with titles matching that regex will be selected
  * @param remotes - optional list of remote ids. If set to "*", all remotes will be matched
  *               when selecting.
  */
case class SMGFilter(px: Option[String],
                     sx: Option[String],
                     rx: Option[String],
                     rxx: Option[String],
                     prx: Option[String],
                     trx: Option[String],
                     remotes: Seq[String],
                     gopts: GraphOptions
                    ) {

  // Local version of the filter (pinned to "local" remote)
  def asLocalFilter: SMGFilter =
    SMGFilter(px = px, sx = sx, rx = rx, rxx = rxx, trx = trx, prx = prx, remotes = Seq(SMGRemote.local.id), gopts = gopts)

  // make regexes case insensitive
  private def ciRegex(so: Option[String]): Option[Regex] = so.map(s => if (s.isEmpty) s else  "(?i)" + s ).
    map( s =>
      Try(s.r).getOrElse(
        "MATCH_NOTHING^".r // XXX The ^ after anything ensures nothing will match
      )
    )
  private val ciRx = ciRegex(rx)
  private val ciRxx = ciRegex(rxx)
  private val ciTrxs = trx.map { s =>
    s.split("\\s+").filter(s => s != "").map(rxs => ciRegex(Some(rxs)).get).toSeq
  }.getOrElse(Seq())
  private val ciPrx = ciRegex(prx)

  lazy val matchesAnyObjectIdAndText: Boolean = px.isEmpty && sx.isEmpty && rx.isEmpty && rxx.isEmpty &&
    trx.isEmpty && prx.isEmpty

  def matches(ob: SMGObjectBase) : Boolean = {
    matchesRemotes(ob.id) && matchesId(ob.id) && matchesText(ob) && matchesParentId(ob)
  }

  private def matchesRemotes(oid: String): Boolean = {
    if ((remotes.contains(SMGRemote.wildcard.id)) || //wildcard remote matches anything
      (remotes.isEmpty && SMGRemote.isLocalObj(oid))) //lack of remotes matches local objects
      true
    else
      remotes.contains(SMGRemote.remoteId(oid))
  }

  private def matchesId(oid: String): Boolean = {
    var ret = true
    if ((px.getOrElse("") != "") && (!SMGRemote.localId(oid).startsWith(px.get))) ret = false
    if ((sx.getOrElse("") != "") && (!SMGRemote.localId(oid).endsWith(sx.get))) ret = false
    if ((rx.getOrElse("") != "") && ciRx.get.findFirstIn(SMGRemote.localId(oid)).isEmpty) ret = false
    if ((rxx.getOrElse("") != "") && ciRxx.get.findFirstIn(SMGRemote.localId(oid)).nonEmpty) ret = false
    ret
  }

  private def matchesText(ob: SMGObjectBase): Boolean = {
    ciTrxs.isEmpty || {
      ciTrxs.forall( rx => rx.findFirstIn(ob.searchText).nonEmpty)
    }
  }

  private def matchesParentId(ob: SMGObjectBase): Boolean = {
    (prx.getOrElse("") == "") ||
      ob.parentIds.exists(pid => ciPrx.get.findFirstIn(SMGRemote.localId(pid)).isDefined)
  }

  def asUrlForPeriod(aPeriod: Option[String] = None): String = {
    val sb = new StringBuilder()
    if (aPeriod.isDefined) sb.append("&period=").append(URLEncoder.encode(aPeriod.get,"UTF-8"))
    if (px.isDefined) sb.append("&px=").append(URLEncoder.encode(px.get,"UTF-8"))
    if (sx.isDefined) sb.append("&sx=").append(URLEncoder.encode(sx.get,"UTF-8"))
    if (rx.isDefined) sb.append("&rx=").append(URLEncoder.encode(rx.get,"UTF-8"))
    if (rxx.isDefined) sb.append("&rxx=").append(URLEncoder.encode(rxx.get,"UTF-8"))
    if (trx.isDefined) sb.append("&trx=").append(URLEncoder.encode(trx.get,"UTF-8"))
    if (prx.isDefined) sb.append("&prx=").append(URLEncoder.encode(prx.get,"UTF-8"))
    remotes.foreach { rmt => sb.append("&remote=").append(URLEncoder.encode(rmt,"UTF-8")) }

    if (gopts.step.isDefined) sb.append("&step=").append(URLEncoder.encode(gopts.step.get.toString,"UTF-8"))
    if (gopts.xsort.isDefined && (gopts.xsort.get != 0))
      sb.append("&xsort=").append(URLEncoder.encode(gopts.xsort.get.toString,"UTF-8"))
    if (gopts.disablePop) sb.append("&dpp=on")
    if (gopts.disable95pRule) sb.append("&d95p=on")

    if (sb.isEmpty)
      ""
    else
      sb.toString.substring(1)
  }

  def asUrlForPage(pg: Int, cols: Option[Int], rows: Option[Int], aPeriod: Option[String] = None): String = {
    val mysb = new StringBuilder(asUrlForPeriod(aPeriod))
    if (cols.isDefined)  mysb.append("&cols=").append(cols.get)
    if (rows.isDefined)  mysb.append("&rows=").append(rows.get)
    if (pg != 0) mysb.append("&pg=").append(pg)
    val ret = mysb.toString()
    if (ret.startsWith("&"))
      ret.substring(1)
    else ret
  }

  def asUrl: String = asUrlForPeriod(None)

  private val paramsIdHumanSeq = Seq(
    if (trx.isDefined) "trx=" + trx.get else "",
    if (px.isDefined) "px=" + px.get else "",
    if (sx.isDefined) "sx=" + sx.get else "",
    if (rx.isDefined) "rx=" + rx.get else "",
    if (rxx.isDefined) "rx exclude=" + rxx.get else "",
    if (prx.isDefined) "prx=" + prx.get else ""
  ).filter(s => s.nonEmpty)

  private val paramsHumanText = if (paramsIdHumanSeq.isEmpty) "*" else paramsIdHumanSeq.mkString(" AND ")

  val humanText: String = paramsHumanText +
    (if (remotes.nonEmpty && (remotes != Seq(SMGRemote.local.id)))
      s" (remotes=${remotes.map{ r =>
        if (r == SMGRemote.local.id) SMGRemote.localName else r }.mkString(",")})"
    else "")
}

object SMGFilter {

  val matchLocal = SMGFilter(None,None,None,None,None,None, Seq(SMGRemote.local.id), GraphOptions.default )

  val matchAll = SMGFilter(None,None,None,None, None, None, Seq(SMGRemote.wildcard.id), GraphOptions.default )

  def fromPrefixWithRemote(px:String, remoteIds: Seq[String]): SMGFilter =
    SMGFilter(Some(px), None, None, None, None, None, remoteIds, GraphOptions.default )

  def fromPrefixLocal(px:String): SMGFilter = fromPrefixWithRemote(px, Seq(SMGRemote.local.id))


  def fromParams(params: Map[String, Seq[String]]): SMGFilter = {
    val gopts = GraphOptions(
      step = params.get("px").map(l => SMGRrd.parseStep(l.head).getOrElse(0)),
      pl = params.get("pl").map(_.head),
      xsort = params.get("xsort").map(_.head.toInt),
      disablePop = params.contains("dpp") && params("dpp").head == "on",
      disable95pRule = params.contains("dpp") && params("dpp").head == "on",
      maxY = params.get("maxy").map(_.head.toDouble),
      minY = params.get("miny").map(_.head.toDouble),
      logY = params.contains("logy") && params("logy").head == "on"
    )
    SMGFilter(
      params.get("px").map(_.head),
      params.get("sx").map(_.head),
      params.get("rx").map(_.head),
      params.get("rxx").map(_.head),
      params.get("prx").map(_.head),
      params.get("trx").map(_.head),
      params.getOrElse("remote", Seq(SMGRemote.local.id)), // TODO or use empty seq here?
      gopts
    )
  }
}
