package com.smule.smg.core

import com.smule.smg.core.IntervalThreadsConfig.PoolType

import scala.util.Try

object IntervalThreadsConfig {
  object PoolType extends Enumeration {
    val FIXED, WORK_STEALING = Value
  }
  val DEFAULT_POOL_TYPE: PoolType.Value = PoolType.FIXED
  val DEFAULT_NUM_THREADS: Int = 4

  def poolTypeFromStr(in: String): PoolType.Value =
    Try(PoolType.withName(in)).getOrElse(DEFAULT_POOL_TYPE)

  def defaultConf(intvl: Int): IntervalThreadsConfig =
    IntervalThreadsConfig(intvl, DEFAULT_NUM_THREADS, DEFAULT_POOL_TYPE)
}

case class IntervalThreadsConfig(interval: Int, numThreads: Int, poolType: PoolType.Value) {
  lazy val inspect: String = s"(interval=$interval,numThreads=$numThreads,poolType=$poolType)"
}
