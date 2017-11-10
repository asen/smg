package com.smule.smg

import java.net.URLEncoder

import play.api.libs.functional.syntax._

import play.api.libs.json._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

case class SMGMonFilter(rx: Option[String],
                        rxx: Option[String],
                        minState: Option[SMGState.Value],
                        includeSoft: Boolean,
                        includeAcked: Boolean,
                        includeSilenced: Boolean
                       ) {

  private lazy val ciRx = SMGMonFilter.ciRegex(rx)
  private lazy val ciRxx = SMGMonFilter.ciRegex(rxx)

  def matchesState(ms: SMGMonState): Boolean = {
    if ((rx.getOrElse("") != "") && ciRx.get.findFirstIn(SMGRemote.localId(ms.id)).isEmpty)
      return false
    if ((rxx.getOrElse("") != "") && ciRxx.get.findFirstIn(SMGRemote.localId(ms.id)).nonEmpty)
      return false
    if (minState.isDefined && minState.get > ms.currentStateVal)
      return false
    if (!(includeSoft || ms.isHard))
      return false
    if (!(includeAcked || !ms.isAcked))
      return false
    if (!(includeSilenced || !ms.isSilenced))
      return false
    true
  }

  def asUrlParams: String = {
    val pairs = ListBuffer[String]()
    if (rx.isDefined) pairs += "rx=" + URLEncoder.encode(rx.get, "UTF-8")
    if (rxx.isDefined) pairs += "rxx=" + URLEncoder.encode(rxx.get, "UTF-8")
    if (minState.isDefined) pairs += "ms=" + minState.get.toString
    if (includeSoft) pairs += "soft=on"
    if (includeAcked) pairs += "ackd=on"
    if (includeSilenced) pairs += "slncd=on"
    pairs.mkString("&")
  }
}

object SMGMonFilter {

  val matchAll = SMGMonFilter(rx = None, rxx = None, minState = None,
    includeSoft = true, includeAcked = true, includeSilenced = true)

  def ciRegex(so: Option[String]): Option[Regex] = so.map(s => if (s.isEmpty) s else  "(?i)" + s ).map(_.r)

  val jsReads: Reads[SMGMonFilter] = {
    (
      (JsPath \ "rx").readNullable[String] and
        (JsPath \ "rxx").readNullable[String] and
        (JsPath \ "mins").readNullable[String].map(sopt => sopt.map(s =>SMGState.fromName(s))) and
        (JsPath \ "isft").readNullable[Int].map(iopt => iopt.getOrElse(1) == 0) and
        (JsPath \ "iack").readNullable[Int].map(iopt => iopt.getOrElse(1) == 0) and
        (JsPath \ "islc").readNullable[Int].map(iopt => iopt.getOrElse(1) == 0)
      ) (SMGMonFilter.apply _)
  }

  val jsWrites: Writes[SMGMonFilter] = new Writes[SMGMonFilter] {
    override def writes(flt: SMGMonFilter): JsValue = {
      val mm = mutable.Map[String,JsValue]()
      if (flt.rx.isDefined) mm += ("rx" -> Json.toJson(flt.rx.get))
      if (flt.rxx.isDefined) mm += ("rxx" -> Json.toJson(flt.rxx.get))
      if (flt.minState.isDefined) mm += ("mins" -> Json.toJson(flt.minState.get.toString))
      if (!flt.includeSoft) mm += ("isft" -> Json.toJson(0))
      if (!flt.includeAcked) mm += ("iack" -> Json.toJson(0))
      if (!flt.includeSilenced) mm += ("islc" -> Json.toJson(0))
      Json.toJson(mm.toMap)
    }
  }
}

