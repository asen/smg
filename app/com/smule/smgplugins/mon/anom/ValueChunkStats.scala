package com.smule.smgplugins.mon.anom

import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable
import scala.math.{max, min, pow}

case class ValueChunkStats(cnt: Int, avg: Double, variance: Double, p90: Double) {

  def serialize: JsValue = {
    val mm = mutable.Map[String, JsValue](
      "cnt" -> Json.toJson(cnt)
    )
    if (!avg.isNaN) mm("avg") = Json.toJson(avg)
    if (!variance.isNaN) mm("variance") = Json.toJson(variance)
    if (!p90.isNaN) mm("p90") = Json.toJson(p90)
    Json.toJson(mm.toMap)
  }

  def isSimilar(changeThresh: Double, oth: ValueChunkStats): Boolean = {
    (max(avg,oth.avg) < changeThresh * min(avg, oth.avg)) ||
      (max(variance, oth.variance) < changeThresh * min(variance, oth.variance)) ||
      (max(p90,oth.p90) < changeThresh * min(p90, oth.p90))
  }
}

object ValueChunkStats {

  def fromSeq(lst: Seq[Double]): ValueChunkStats = {
    if (lst.isEmpty) return ValueChunkStats(0, 0.0, 0.0, 0.0)
    val cnt = lst.size
    val avg = lst.sum / lst.size
    val variance = lst.map(v => pow(v - avg, 2)).sum / cnt
    val lstsz = lst.size
    val p90 = if (lstsz == 1) lst.head else lst.sorted.take( (lstsz * 9) / 10 ).lastOption.getOrElse(0.0)
    ValueChunkStats(cnt, avg, variance, p90)
  }

  def aggregate(lst: Seq[ValueChunkStats]): ValueChunkStats = {
    val newCnt = lst.map(_.cnt).sum
    val newAvg = lst.map(cs => cs.avg * cs.cnt).sum / newCnt
    val newVariance = lst.map(cs => cs.variance * cs.cnt).sum / newCnt
    val newP90 = if (lst.isEmpty) Double.NaN else lst.map(_.p90).max
    ValueChunkStats(newCnt, newAvg, newVariance, newP90)
  }

  def deserialize(src: JsValue): ValueChunkStats = {
    val cnt = (src \ "cnt").asOpt[Int].getOrElse(0)
    val avg = (src \ "avg").asOpt[Double].getOrElse(0.0)
    val variance = (src \ "variance").asOpt[Double].getOrElse(0.0)
    val p90 = (src \ "p90").asOpt[Double].getOrElse(0.0)
    ValueChunkStats(cnt, avg, variance, p90)
  }
}

