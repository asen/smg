package com.smule.smg

import java.net.URLEncoder

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
  * @param remote - optional remote id. if None, only local objecs will be selected. If set to a remote id,
  *               only objects from that remote will be selected. If set to "*", all remotes will be matched
  *               when selecting.
  */
case class SMGFilter(px: Option[String],
                     sx: Option[String],
                     rx: Option[String],
                     rxx: Option[String],
                     trx: Option[String],
                     remote: Option[String],
                     gopts: GraphOptions
                    ) {

  // make regexes case insensitive
  private def ciRegex(so: Option[String]) = so.map( s => if (s.isEmpty) s else  "(?i)" + s ).map(_.r)
  private val ciRx = ciRegex(rx)
  private val ciRxx = ciRegex(rxx)
  private val ciTrxs = trx.map { s =>
    s.split("\\s+").filter(s => s != "").map(rxs => ciRegex(Some(rxs)).get).toSeq
  }.getOrElse(Seq())

  lazy val matchesAnyObjectIdAndText = px.isEmpty && sx.isEmpty && rx.isEmpty && rxx.isEmpty && trx.isEmpty

  def matches(ob: SMGObjectBase) : Boolean = {
    matchesId(ob.id) && matchesText(ob)
  }

  private def matchesId(oid: String) = {
    var ret = true
    if ((px.getOrElse("") != "") && (!SMGRemote.localId(oid).startsWith(px.get))) ret = false
    if ((sx.getOrElse("") != "") && (!SMGRemote.localId(oid).endsWith(sx.get))) ret = false
    if ((rx.getOrElse("") != "") && ciRx.get.findFirstIn(SMGRemote.localId(oid)).isEmpty) ret = false
    if ((rxx.getOrElse("") != "") && ciRxx.get.findFirstIn(SMGRemote.localId(oid)).nonEmpty) ret = false
    ret
  }

  private def matchesText(ob: SMGObjectBase) = {
    ciTrxs.isEmpty || {
      ciTrxs.forall( rx => rx.findFirstIn(ob.searchText).nonEmpty)
    }
  }

  def asUrlForPeriod(aPeriod: Option[String] = None): String = {
    val sb = new StringBuilder("period=").append(URLEncoder.encode(aPeriod.getOrElse(GrapherApi.defaultPeriod),"UTF-8"))
    if (px.isDefined) sb.append("&px=").append(URLEncoder.encode(px.get,"UTF-8"))
    if (sx.isDefined) sb.append("&sx=").append(URLEncoder.encode(sx.get,"UTF-8"))
    if (rx.isDefined) sb.append("&rx=").append(URLEncoder.encode(rx.get,"UTF-8"))
    if (rxx.isDefined) sb.append("&rxx=").append(URLEncoder.encode(rxx.get,"UTF-8"))
    if (trx.isDefined) sb.append("&trx=").append(URLEncoder.encode(trx.get,"UTF-8"))
    if (remote.isDefined) sb.append("&remote=").append(URLEncoder.encode(remote.get,"UTF-8"))

    if (gopts.step.isDefined) sb.append("&step=").append(URLEncoder.encode(gopts.step.get.toString,"UTF-8"))
    if (gopts.xsort.isDefined) sb.append("&xsort=").append(URLEncoder.encode(gopts.xsort.get.toString,"UTF-8"))
    if (gopts.disablePop) sb.append("&dpp=on")
    if (gopts.disable95pRule) sb.append("&d95p=on")

    sb.toString
  }

  def asUrlForPage(pg: Int, cols: Option[Int], rows: Option[Int], aPeriod: Option[String] = None): String = {
    val mysb = new StringBuilder(asUrlForPeriod(aPeriod))
    if (cols.isDefined)  mysb.append("&cols=").append(cols.get)
    if (rows.isDefined)  mysb.append("&rows=").append(rows.get)
    if (pg != 0) mysb.append("&pg=").append(pg)
    mysb.toString
  }

  def asUrl = asUrlForPeriod(None)

  private val paramsIdHumanSeq = Seq(
    if (trx.isDefined) "trx=" + trx.get else "",
    if (px.isDefined) "px=" + px.get else "",
    if (sx.isDefined) "sx=" + sx.get else "",
    if (rx.isDefined) "rx=" + rx.get else "",
    if (rxx.isDefined) "rx exclude=" + rxx.get else ""
  ).filter(s => s.nonEmpty)

  private val paramsHumanText = if (paramsIdHumanSeq.isEmpty) "*" else paramsIdHumanSeq.mkString(" AND ")

  val humanText = paramsHumanText + (if (remote.isDefined) s" (remote=${remote.get})" else "")
}

object SMGFilter {

  val matchLocal = SMGFilter(None,None,None,None,None, None, GraphOptions() )

  val matchAll = SMGFilter(None,None,None,None,None, Some(SMGRemote.wildcard.id), GraphOptions() )

  def fromPrefixWithRemote(px:String, remoteId: Option[String]) = SMGFilter(Some(px), None, None, None, None, remoteId, GraphOptions() )

  def fromPrefixLocal(px:String) = fromPrefixWithRemote(px, None)


  def fromParams(params: Map[String, Seq[String]]) = {
    val gopts = GraphOptions(
      step = params.get("px").map(l => SMGRrd.parseStep(l.head).getOrElse(0)),
      pl = params.get("pl").map(_.head),
      xsort = params.get("xsort").map(_.head.toInt),
      disablePop = params.contains("dpp") && params("dpp").head == "on",
      disable95pRule = params.contains("dpp") && params("dpp").head == "on",
      maxY = params.get("maxy").map(_.head.toDouble)
    )
    SMGFilter(
      params.get("px").map(_.head),
      params.get("sx").map(_.head),
      params.get("rx").map(_.head),
      params.get("rxx").map(_.head),
      params.get("trx").map(_.head),
      params.get("remote").map(_.head),
      gopts
    )
  }
}
