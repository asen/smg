package com.smule.smg

import scala.concurrent.ExecutionContext

/**
 * Created by asen on 10/24/15.
 */

trait ExecutionContexts {
  def defaultCtx: ExecutionContext
  def rrdGraphCtx: ExecutionContext
  def monitorCtx: ExecutionContext
  def ctxForInterval(interval: Int): ExecutionContext
  def initializeUpdateContexts(intervals: Seq[Int],
                               threadsPerIntervalMap: Map[Int,Int],
                               defaultThreadsPerInterval: Int): Unit
}
