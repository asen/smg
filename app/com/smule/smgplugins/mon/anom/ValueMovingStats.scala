package com.smule.smgplugins.mon.anom

import com.smule.smg.core.SMGLoggerApi
import com.smule.smg.rrd.SMGRrd
import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable
import scala.math._

//Mutable short-term values + long-term averaged stats object
class ValueMovingStats(val idStr: String, log: SMGLoggerApi) {

  // "short term" values up to given (in update) size
  val stVals = new mutable.Queue[Double]()
  // previous "short term" values - to be aggregated into a long-term stat when given size is exceeded
  val prevStVals = new mutable.Queue[Double]()
  // long term stats - a moving queue of fixed max size where older objects are discarded when new are added in
  // the actual objects are a struct of cnt/avg/variance values calculated from prevStVals on aggregation
  val ltStats = new mutable.Queue[ValueChunkStats]()

  var lastUpdateTs = 0

  //calc cnt/avg/variance object from the short term stats
  def calcStStat: ValueChunkStats = ValueChunkStats.fromSeq(prevStVals.toList ++ stVals.toList)

  // calculate a long-term cnt/avg/variance object using the ltStats
  def calcLtAggStat: ValueChunkStats = ValueChunkStats.aggregate(ltStats.toList)

  // long term chunks contain overlapping maxStCnt intervals where each long term stat is
  // offset-ed with half maxStCnt data points. never return less than 2
  def maxLtStatsSize(maxStCnt: Int, maxLtCnt: Int): Int = max(maxLtCnt / (maxStCnt / 2), 2)

  def maxGap(maxStCnt: Int, interval: Int): Int = max(maxStCnt * interval / 2, 2 * interval) // at least 2 intervals


  // Update the stats with a new value. also provide (possibly updated via confg) max "short term" and "long-term"
  // data points to consider
  def update(interval:Int, ts:Int, fetchedVal: Double, maxStCnt: Int, maxLtCnt: Int ): Unit = {
    // sanity checks first
    if (maxLtCnt < maxStCnt) {
      log.error(s"ValueMovingStats.update [$idStr]: maxLtCnt < maxStCnt ($maxLtCnt < $maxStCnt)")
      return
    }

    // check for and avoid multiple updates in the same interval
    val tsDiff = ts - lastUpdateTs
    if (tsDiff < (interval / 2)) {
      log.warn(s"ValueMovingStats.update [$idStr]: ignoring update due to tsDiff=$tsDiff < interval=$interval / 2")
      return
    }

    val maxg = maxGap(maxStCnt, interval)
    if (tsDiff > maxg) {
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
      while (prevStVals.lengthCompare(maxPrevStCnt) >= 0 ) {
        val chunkToCondense = (prevStVals ++ stVals).take(maxStCnt)
        // condense the chunk and add to long term list
        val newLtStats = ValueChunkStats.fromSeq(chunkToCondense)
        // drop entries from the prevStVals and ltStats Queues
        (1 to maxPrevStCnt).foreach(_ => prevStVals.dequeue())
        val maxLtSz = maxLtStatsSize(maxStCnt, maxLtCnt)
        val ltStatsCountToDrop = if (ltStats.lengthCompare(maxLtSz) < 0) 0 else ltStats.size - maxLtSz+ 1
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

  def hasEnoughData(maxStCnt: Int, maxLtCnt: Int): Boolean = (maxStCnt > 0) &&  // sanity  check
    (maxLtCnt > maxStCnt) && // sanity check
    (maxLtStatsSize(maxStCnt, maxLtCnt) * 0.75 <= ltStats.size)  // require at least 75% of the max long term stats

  def checkAnomaly(interval: Int, changeThresh: Double, maxStCnt: Int, maxLtCnt: Int,
                   numFmt: (Double) => String): Option[String] = {
    // sanity checks first

    val maxg = maxGap(maxStCnt, interval)
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
      lazy val varianceStr = s"(stVariance=${numFmt(stStat.variance)}/" +
        s"stddev=${numFmt(scala.math.sqrt(stStat.variance))}/p90=${numFmt(stStat.p90)}, " +
        s"ltVariance=${numFmt(ltAggStat.variance)}/stddev=${numFmt(scala.math.sqrt(ltAggStat.variance))}/" +
        s"p90=${numFmt(ltAggStat.p90)})"
      lazy val ltStStr = s"stAvg=${numFmt(stStat.avg)}/$changeThresh/ltAvg=${numFmt(ltAggStat.avg)}"
      // average increase -> spike
      // and one additional check - must be an increase compared to all individual ltStats
      if ((stStat.avg > changeThresh * ltAggStat.avg) && ltStats.forall(_.avg <= stStat.avg))
        return Some(s"SPIKE: $ltStStr $varianceStr")
      // average decrease -> drop
      // and one additional check - must be a drop compared to all individual ltStats
      if ((ltAggStat.avg > changeThresh * stStat.avg) && ltStats.forall(_.avg >= stStat.avg))
        return Some(s"DROP: $ltStStr $varianceStr")
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

object ValueMovingStats {
  def deserialize(src: JsValue, tgt: ValueMovingStats): Unit = {

    val lastUpdateTsJsv = src \ "lastUpdateTs"

    if (lastUpdateTsJsv.toOption.isDefined){
      tgt.lastUpdateTs = lastUpdateTsJsv.as[Int]
      // TODO check age? (will be discarded if too old on next update anyway)
    } else tgt.lastUpdateTs = SMGRrd.tssNow //TODO temp - for backwards compatibility


    val stValsJsv = src \ "stVals"
    if (stValsJsv.toOption.isDefined) {
      stValsJsv.as[List[Double]].foreach(v => tgt.stVals += v)
    }

    val prevStValsJsv = src \ "prevStVals"
    if (prevStValsJsv.toOption.isDefined) {
      prevStValsJsv.as[List[Double]].foreach(v => tgt.prevStVals += v)
    }

    val ltStatsJsv = src \ "ltStats"
    if (ltStatsJsv.toOption.isDefined) {
      ltStatsJsv.as[List[JsValue]].foreach(v => tgt.ltStats += ValueChunkStats.deserialize(v))
    }
  }
}

