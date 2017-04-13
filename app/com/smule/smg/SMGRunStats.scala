package com.smule.smg

import java.util.concurrent.atomic.AtomicInteger

/**
  * A singleton dedicated to gathering stats updated by multiple update actors
  */
object SMGRunStats {
  private val log = SMGLogger

  private val customCounts = new java.util.concurrent.ConcurrentHashMap[String,AtomicInteger]()
  private val customTotals = new java.util.concurrent.ConcurrentHashMap[String,Int]()

  def resetCustomCounter(cid: String, total: Int): Unit = {
    log.info("SMGRunStats.resetCustomCounter(" + cid + "): total=" + total)
    val prevCount = customCounts.getOrDefault(cid, new AtomicInteger(0)).get()
    val prevTotal = customTotals.getOrDefault(cid, 0)
    if (prevCount < prevTotal) {
      log.error("SMGRunStats.resetCustomCounter(" + cid + "): Previous run processed less objects than total: " +
        prevCount + " < " + prevTotal)
    }
    customCounts.put(cid, new AtomicInteger(0))
    customTotals.put(cid, total)
  }

  /**
    * Will get true only once (for given counter id) - when the count reached total
    * @param cid - counter id
    * @return
    */
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
      true
    } else false
  }

}

