package com.smule.smg

import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.math._

case class SMGMonValueChunkStats(cnt: Int, avg: Double, variance: Double, p90: Double) {

  def serialize: JsValue = {
    val mm = mutable.Map[String, JsValue](
      "cnt" -> Json.toJson(cnt)
    )
    if (!avg.isNaN) mm("avg") = Json.toJson(avg)
    if (!variance.isNaN) mm("variance") = Json.toJson(variance)
    if (!p90.isNaN) mm("p90") = Json.toJson(p90)
    Json.toJson(mm.toMap)
  }

  def isSimilar(changeThresh: Double, oth: SMGMonValueChunkStats): Boolean = {
    (max(avg,oth.avg) < changeThresh * min(avg, oth.avg)) ||
      (max(variance, oth.variance) < changeThresh * min(variance, oth.variance)) ||
      (max(p90,oth.p90) < changeThresh * min(p90, oth.p90))
  }
}

object SMGMonValueChunkStats {

  def fromSeq(lst: Seq[Double]): SMGMonValueChunkStats = {
    if (lst.isEmpty) return SMGMonValueChunkStats(0, 0.0, 0.0, 0.0)
    val cnt = lst.size
    val avg = lst.sum / lst.size
    val variance = lst.map(v => pow(v - avg, 2)).sum / cnt
    val lstsz = lst.size
    val p90 = if (lstsz == 1) lst.head else lst.sorted.take( (lstsz * 9) / 10 ).lastOption.getOrElse(0.0)
    SMGMonValueChunkStats(cnt, avg, variance, p90)
  }

  def aggregate(lst: Seq[SMGMonValueChunkStats]): SMGMonValueChunkStats = {
    val newCnt = lst.map(_.cnt).sum
    val newAvg = lst.map(cs => cs.avg * cs.cnt).sum / newCnt
    val newVariance = lst.map(cs => cs.variance * cs.cnt).sum / newCnt
    val newP90 = if (lst.isEmpty) Double.NaN else lst.map(_.p90).max
    SMGMonValueChunkStats(newCnt, newAvg, newVariance, newP90)
  }

  def deserialize(src: JsValue): SMGMonValueChunkStats = {
    val cnt = (src \ "cnt").asOpt[Int].getOrElse(0)
    val avg = (src \ "avg").asOpt[Double].getOrElse(0.0)
    val variance = (src \ "variance").asOpt[Double].getOrElse(0.0)
    val p90 = (src \ "p90").asOpt[Double].getOrElse(0.0)
    // TODO XXX temp code to workaround a previous serialization bug - use p90 instead of broken variance and avg
    val fixedVariance = if ((variance == cnt.toDouble) || (variance == 0.0)) p90 else variance
    val fixedAvg = if (avg == 0.0) p90 else avg
    SMGMonValueChunkStats(cnt, fixedAvg, fixedVariance, p90)
  }
}

//Mutable short-term values + long-term averaged stats object
class SMGMonValueMovingStats(val ouid: String, val vix: Int, interval: Int) {

  val log = SMGLogger

  private lazy val idIxStr = s"$ouid:$vix"

  // "short term" values up to given (in update) size
  val stVals = new mutable.Queue[Double]()
  // previous "short term" values - to be aggregated into a long-term stat when given size is exceeded
  val prevStVals = new mutable.Queue[Double]()
  // long term stats - a moving queue of fixed max size where older objects are discarded when new are added in
  // the actual objects are a struct of cnt/avg/variance values calculated from prevStVals on aggregation
  val ltStats = new mutable.Queue[SMGMonValueChunkStats]()

  var lastUpdateTs = 0

  //calc cnt/avg/variance object from the short term stats
  def calcStStat = SMGMonValueChunkStats.fromSeq(prevStVals.toList ++ stVals.toList)

  // calculate a long-term cnt/avg/variance object using the ltStats
  def calcLtAggStat = SMGMonValueChunkStats.aggregate(ltStats.toList)

  // long term chunks contain overlapping maxStCnt intervals where each long term stat is
  // offset-ed with half maxStCnt data points. never return less than 2
  def maxLtStatsSize(maxStCnt: Int, maxLtCnt: Int) = max(maxLtCnt / (maxStCnt / 2), 2)

  def maxGap(maxStCnt: Int) = max(maxStCnt * interval / 2, 2 * interval) // at least 2 intervals


  // Update the stats with a new value. also provide (possibly updated via confg) max "short term" and "long-term"
  // data points to consider
  def update(ts:Int, fetchedVal: Double, maxStCnt: Int, maxLtCnt: Int ): Unit = {
    // sanity checks first
    if (maxLtCnt < maxStCnt) {
      log.error(s"SMGMonValueMovingStats.update [$idIxStr]: maxLtCnt < maxStCnt ($maxLtCnt < $maxStCnt)")
      return
    }

    val maxg = maxGap(maxStCnt)
    val tsDiff = ts - lastUpdateTs
    if (tsDiff > maxg) {
      if (lastUpdateTs != 0) // do not log the an initial reset()
        log.info(s"SMGMonValueMovingStats.update [$idIxStr]: resetting stats due to tsDiff=$tsDiff, maxGap=$maxg")
      reset()
    }
    lastUpdateTs = ts

    // also handle the cases when maxXXXCount has decreased via conf and drop/condense more data points
    val stCnt = stVals.size
    val stCountToDrop = if (stCnt < maxStCnt) {
      0
    }  else {
      stCnt - maxStCnt + 1
    }
    (1 to stCountToDrop).foreach { i =>
      val newLtVal = stVals.dequeue()
      prevStVals += newLtVal
      val maxPrevStCnt = maxStCnt / 2
      while (prevStVals.size >= maxPrevStCnt ) {
        val chunkToCondense = (prevStVals ++ stVals).take(maxStCnt)
        // condense the chunk and add to long term list
        val newLtStats = SMGMonValueChunkStats.fromSeq(chunkToCondense)
        // drop entries from the prevStVals and ltStats Queues
        (1 to maxPrevStCnt).foreach(_ => prevStVals.dequeue())
        val maxLtSz = maxLtStatsSize(maxStCnt, maxLtCnt)
        val ltStatsCountToDrop = if (ltStats.size < maxLtSz) 0 else ltStats.size - maxLtSz+ 1
        (1 to ltStatsCountToDrop).foreach(_ => ltStats.dequeue())
        ltStats += newLtStats
      }
    }
    stVals += fetchedVal
  }

  def reset(): Unit = {
    stVals.clear()
    prevStVals.clear()
    ltStats.clear()
  }

  def hasEnoughData(maxStCnt: Int, maxLtCnt: Int) = (maxStCnt > 0) &&  // sanity  check
    (maxLtCnt > maxStCnt) && // sanity check
    (maxLtStatsSize(maxStCnt, maxLtCnt) * 0.75 <= ltStats.size)  // require at least 75% of the max long term stats

  def checkAnomaly(changeThresh: Double, maxStCnt: Int, maxLtCnt: Int): Option[String] = {
    // sanity checks first

    val maxg = maxGap(maxStCnt)
    val tsDiff = SMGRrd.tssNow - lastUpdateTs
    if (tsDiff > maxg)
      return None  // data too old to consider.


    if (!hasEnoughData(maxStCnt, maxLtCnt))
      return None // not enough data points to determine anomaly

    val stStat = calcStStat // cache calculated short term stats to be used below

    // zero is anomaly compared to pretty much anything else
    // if the entire "short term" data is all 0's this is not an anomaly
    if ((stStat.avg == 0.0) && (stStat.variance == 0.0))
      return None

    // first check if our short term period is different than all previous ones
    // covers "seasonality" and occasional spikes from 0.0
    val isNormal = ltStats.exists(lts => stStat.isSimilar(changeThresh, lts))
    if (isNormal)
      return None

    // get the long term "aggregate" stats, to be compared with stStats below
    val ltAggStat = calcLtAggStat

    // get the bigger and smaller variances
    val minVar = scala.math.min(stStat.variance, ltAggStat.variance)
    val maxVar = scala.math.max(stStat.variance, ltAggStat.variance)
    // and bigger and smaller 90%-iles
    val minP90 = scala.math.min(stStat.p90, ltAggStat.p90)
    val maxP90 = scala.math.max(stStat.p90, ltAggStat.p90)

    // if variances and 95%-iles are too different we probably have a drop or spike
    if ((maxVar > changeThresh * minVar) && (maxP90 > changeThresh * minP90)) {
      lazy val varianceStr = s" (stVariance=${stStat.variance}/stddev=${scala.math.sqrt(stStat.variance)}/p90=${stStat.p90}, " +
        s"ltVariance=${ltAggStat.variance}/stddev=${scala.math.sqrt(ltAggStat.variance)}/p90=${ltAggStat.p90})"
      // average increase -> spike
      // and one additional check - must be an increase compared to all individual ltStats
      if ((stStat.avg > changeThresh * ltAggStat.avg) && ltStats.forall(_.avg <= stStat.avg))
        return Some(s"SPIKE: stAvg=${stStat.avg} gt $changeThresh * ltAvg=${ltAggStat.avg} " +
          varianceStr)
      // average decrease -> drop
      // and one additional check - must be a drop compared to all individual ltStats
      if ((ltAggStat.avg > changeThresh * stStat.avg) && ltStats.forall(_.avg >= stStat.avg))
        return Some(s"DROP: ltAvg=${ltAggStat.avg} gt $changeThresh * stAvg=${stStat.avg} " +
          varianceStr)
    }

    None
  }

  def serialize : JsValue = {
    val m = Map[String,JsValue](
      "stVals" -> Json.toJson(stVals.toList),
      "prevStVals" -> Json.toJson(prevStVals.toList),
      "ltStats" -> Json.toJson(ltStats.toList.map(_.serialize)),
      "lastUpdateTs" -> Json.toJson(lastUpdateTs),
      // XXX these below are serialized only for convenience - to show via inspect
      "stValsSize" -> Json.toJson(stVals.size),
      "prevStValsSize" -> Json.toJson(prevStVals.size),
      "ltStatsSize" -> Json.toJson(ltStats.size),
      "calcStStat" -> Json.toJson(calcStStat.serialize),
      "calcLtAggStat" -> Json.toJson(calcLtAggStat.serialize)
    )
    Json.toJson(m)
  }

}

object SMGMonValueMovingStats {
  def deserialize(src: JsValue, ouid: String, vix: Int, interval: Int): SMGMonValueMovingStats = {
    val ret = new SMGMonValueMovingStats(ouid, vix, interval)

    val lastUpdateTsJsv = src \ "lastUpdateTs"

    if (lastUpdateTsJsv.toOption.isDefined){
      ret.lastUpdateTs = lastUpdateTsJsv.as[Int]
      // TODO check age? (will be discarded if too old on next update anyway)
    } else ret.lastUpdateTs = SMGRrd.tssNow //TODO temp - for backwards compatibility


    val stValsJsv = src \ "stVals"
    if (stValsJsv.toOption.isDefined) {
      stValsJsv.as[List[Double]].foreach(v => ret.stVals += v)
    }

    val prevStValsJsv = src \ "prevStVals"
    if (prevStValsJsv.toOption.isDefined) {
      prevStValsJsv.as[List[Double]].foreach(v => ret.prevStVals += v)
    }

    val ltStatsJsv = src \ "ltStats"
    if (ltStatsJsv.toOption.isDefined) {
      ltStatsJsv.as[List[JsValue]].foreach(v => ret.ltStats += SMGMonValueChunkStats.deserialize(v))
    }

    ret
  }
}
