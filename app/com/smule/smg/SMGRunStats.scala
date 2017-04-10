package com.smule.smg

import java.util.concurrent.atomic.AtomicInteger

/**
  * Created by asen on 11/15/15.
  */

/**
  * A singleton dedicated to gathering stats updated by multiple update actors
  */
object SMGRunStats {
  val log = SMGLogger


  val customCounts= new java.util.concurrent.ConcurrentHashMap[String,AtomicInteger]()
  val customTotals = new java.util.concurrent.ConcurrentHashMap[String,Int]()

  def resetCustomCounter(cid: String, total: Int): Unit = {
    log.info("SMGRunStats.resetCustomCounter(" + cid + "): total=" + total)
    val prevCount = customCounts.getOrDefault(cid, new AtomicInteger(0)).get()
    val prevTotal = customTotals.getOrDefault(cid, 0)
    if (prevCount < prevTotal) {
      log.warn("SMGRunStats.resetCustomCounter(" + cid + "): Previous run processed less objects than total: " +
        prevCount + " < " + prevTotal)
    }
    customCounts.put(cid, new AtomicInteger(0))
    customTotals.put(cid, total)
  }

  def incCustomCounter(cid: String): Boolean = {
    var value: AtomicInteger = customCounts.get(cid);
    var cnt = 1
    if (value == null) {
      value = customCounts.putIfAbsent(cid, new AtomicInteger(1));
    }
    if (value != null) {
      cnt = value.incrementAndGet();
    }
    val tl = customTotals.getOrDefault(cid, -1)
    if ((tl > 0) && (cnt == tl)) {
      log.info("SMGRunStats.incCustomCounter(" + cid + ") run completed with " + tl + " objects processed")
      return true
    }
    return false
  }


}

