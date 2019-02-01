package com.smule.smg.monitor

import java.util.{Date, UUID}

import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.collection.mutable

case class SMGMonStickySilence(flt: SMGMonFilter, silenceUntilTs: Int, desc: Option[String], uid: Option[String] = None) {
  val uuid: String = if (uid.isDefined) uid.get else UUID.randomUUID().toString

  val humanDesc: String = (if (flt.rx.isDefined && flt.rxx.isDefined)
    s"regex=${flt.rx.get}, regex exclude=${flt.rxx.get}"
  else if (flt.rx.isDefined)
    s"regex=${flt.rx.get}"
  else if (flt.rxx.isDefined)
    s"regex exclude=${flt.rxx.get}"
  else
    "* (match anything)") + ", until " + new Date(silenceUntilTs.toLong * 1000).toString
}

object SMGMonStickySilence {
  implicit private val smgMonFilterReads: Reads[SMGMonFilter] = SMGMonFilter.jsReads

  def jsReads(pxFn: (String) => String): Reads[SMGMonStickySilence] = {
    (
      (JsPath \ "flt").read[SMGMonFilter] and
        (JsPath \ "slu").read[Int] and
        (JsPath \ "desc").readNullable[String] and
        (JsPath \ "uid").read[String].map(uid => Some(pxFn(uid)))
      ) (SMGMonStickySilence.apply _)
  }

  implicit private val smgMonFilterWrites: Writes[SMGMonFilter] = SMGMonFilter.jsWrites

  val jsWrites: Writes[SMGMonStickySilence] = new Writes[SMGMonStickySilence] {
    override def writes(slc: SMGMonStickySilence): JsValue = {
      val mm = mutable.Map(
        "flt" -> Json.toJson(slc.flt),
        "slu" -> Json.toJson(slc.silenceUntilTs),
        "uid" -> Json.toJson(slc.uuid)
      )
      if (slc.desc.isDefined) mm += ("desc" -> Json.toJson(slc.desc.get))
      Json.toJson(mm.toMap)
    }
  }
}