package com.smule.smg.core

import com.smule.smg.rrd.SMGRrd

import scala.collection.concurrent.TrieMap
import scala.util.Try

/**
  * Mutable store for keeping a cache of recently fetched values.
  */
class SMGValuesCache(log: SMGLoggerApi) {

  private def maxCacheAge(ou: SMGObjectUpdate) = (ou.interval.toDouble * 2.5).toInt

  private def ckey(ou: SMGObjectUpdate) = s"${ou.id}:${ou.interval}"

  case class CachedVals(tss: Int, vals: List[Double])


  private val myPrevCache = new TrieMap[String,CachedVals]()
  private val myLastCache = new TrieMap[String,CachedVals]()


  /**
    * Store recently fetched object value into cache.
    * @param ou - object update
    * @param tss - fetch timestamp (seconds)
    * @param vals - fetched values
    */
  def cacheValues(ou: SMGObjectUpdate, tss: Int, vals: List[Double]): Unit = {
    if (tss < 0)
      log.error(s"SMGValuesCache.cacheValues(${ou.id}): negative tss: $tss vals=$vals")
    val key = ckey(ou)
    val prev = myLastCache.get(key)
    myLastCache.put(key, CachedVals(tss, vals))
    if (ou.isCounter && prev.isDefined) { // keep track of previous value for counters
      myPrevCache.put(key, prev.get)
    }
  }

  private def myInvalidateCache(key: String): Unit = {
    myPrevCache.remove(key)
    myLastCache.remove(key)
  }

  def invalidateCache(ou: SMGObjectUpdate): Unit = {
    myInvalidateCache(ckey(ou))
  }

  /**
    * Get the latest cached values for given object
    * @param ou - object update
    * @return - list of vals if (existing and valid) in cache, list of NaNs otherwise
    */
  def getCachedValues(ou: SMGObjectUpdate, counterAsRate: Boolean): (List[Double], Option[Int]) = {
    lazy val nanList: List[Double] = ou.vars.map(v => Double.NaN)
    val key = ckey(ou)
    val opt = myLastCache.get(key)
    if (opt.isDefined && (SMGRrd.tssNow - opt.get.tss < maxCacheAge(ou))) {
      if (ou.isCounter) {
        val prevOpt = myPrevCache.get(key)
        if (prevOpt.isDefined) {
          // XXX this is to deal with counter overflows and resets which we don't want to mess our aggregated stats
          // but also needed when counter values are requested as rate
          val deltaTime = opt.get.tss - prevOpt.get.tss
          if (deltaTime > 0 && deltaTime < maxCacheAge(ou)) {
            val rates = opt.get.vals.zip(prevOpt.get.vals).map { case (cur, prev) => (cur - prev) / deltaTime }
            val isGood = rates.zip(ou.vars).forall { case (rate, v) =>
              val (minCompareRate, maxCompareRate) = ou.getVarMinMaxCompareValues(v)
              (!rate.isNaN) && (rate >= minCompareRate) && (maxCompareRate >= rate)
            }
            if (isGood)
              if (counterAsRate)
                (rates, Some(opt.get.tss))
              else
                (opt.get.vals, Some(opt.get.tss))
            else {
              (nanList, None) // rates not good
            }
          } else {
            (nanList, None) // time delta outside range
          }
        } else  (nanList, None)
      } else //not a counter
        (opt.get.vals, Some(opt.get.tss))
    } else  (nanList, None)
  }

  def purgeObsoleteObjs(newObjs: Seq[SMGObjectUpdate]): Unit = {
    val newKeysSet = newObjs.map(ou => ckey(ou)).toSet
    val toDel = (myLastCache.keySet.toSet -- newKeysSet) ++ (myPrevCache.keySet.toSet -- newKeysSet)
    toDel.foreach { key =>
      myInvalidateCache(key)
    }
  }
}
