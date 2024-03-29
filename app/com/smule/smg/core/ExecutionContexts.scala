package com.smule.smg.core

import scala.concurrent.ExecutionContext

/**
 * Created by asen on 10/24/15.
 */

trait ExecutionContexts {
  def defaultCtx: ExecutionContext
  def rrdGraphCtx: ExecutionContext
  def monitorCtx: ExecutionContext
  def pluginsSharedCtx: ExecutionContext
  def ctxForInterval(interval: Int): ExecutionContext
  def initializeUpdateContexts(intervals: Map[Int,IntervalThreadsConfig]): Unit
}
