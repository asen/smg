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

  private val countsPerInterval = new java.util.concurrent.ConcurrentHashMap[Int,AtomicInteger]()
  private val totalsPerInterval = new java.util.concurrent.ConcurrentHashMap[Int,Int]()
  private val prevResetPerInterval = new java.util.concurrent.ConcurrentHashMap[Int,Int]()

  // reset interval but only if previous run has finished
  def resetInterval(interval: Int, total: Int): Boolean = {
    val prevCount = countsPerInterval.getOrDefault(interval, new AtomicInteger(0)).get()
    val prevTotal = totalsPerInterval.getOrDefault(interval, 0)
    val resetTs = (System.currentTimeMillis() / 1000).toInt
    val prevResetTs = prevResetPerInterval.getOrDefault(interval, resetTs)
    if (prevCount < prevTotal) {
      if ((resetTs - prevResetTs) < (5 * interval)) {
        log.error("SMGRunStats.resetInterval(interval=" + interval +
          s"): Previous run processed less objects than total (prevResetTs=$prevResetTs, resetTs=$resetTs): " +
          prevCount + " < " + prevTotal + s" (aborting - last reset ${resetTs - prevResetTs} seconds ago)")
        return false
      } else {
        log.error("SMGRunStats.resetInterval(interval=" + interval +
          s"): Previous run processed less objects than total (prevResetTs=$prevResetTs, resetTs=$resetTs): " +
          prevCount + " < " + prevTotal + s" (force resetting - last reset ${resetTs - prevResetTs} seconds ago)")
      }
    }
    prevResetPerInterval.put(interval, resetTs)
    countsPerInterval.put(interval, new AtomicInteger(0))
    totalsPerInterval.put(interval, total)
    true
  }

  def incIntervalCount(interval: Int): Boolean = {
    var value: AtomicInteger = countsPerInterval.get(interval);
    var cnt = 1
    if (value == null) {
      value = countsPerInterval.putIfAbsent(interval, new AtomicInteger(1));
    }
    if (value != null) {
      cnt = value.incrementAndGet();
    }
    val tl = totalsPerInterval.getOrDefault(interval, -1)
    if ((tl > 0) && (cnt == tl)) {
      log.info("SMGRunStats: interval=" + interval + " run completed with " + tl + " commands executed")
      return true
    }
    // sanity check, for now just log the condition
    if (cnt > tl) {
      log.error("SMGRunStats: interval=" + interval + " count is more than total: cnt=" + cnt + " tl=" +  tl)
    }
    false
  }


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

