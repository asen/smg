package com.smule.smg

import java.util.concurrent.{ConcurrentHashMap, Executors}

import javax.inject.{Inject, Singleton}
import play.libs.Akka

import scala.collection.concurrent
import scala.concurrent.ExecutionContext

import scala.collection.JavaConversions._

/**
  * The various execution contexts used by SMG
  */
@Singleton
class SMGExecutionContexts @Inject() () extends ExecutionContexts {
  /**
    * The default (Akka/Play) context used for Akka message communications
    */
  val defaultCtx: ExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext

  /**
    * Context used when executing external rrdtool commands to graph images
    */
  val rrdGraphCtx: ExecutionContext = Akka.system.dispatchers.lookup("akka-contexts.rrd-graph")

  val monitorCtx: ExecutionContext = Akka.system.dispatchers.lookup("akka-contexts.monitor")

  private val log = SMGLogger

  private val ctxMap: concurrent.Map[Int,ExecutionContext] = new ConcurrentHashMap[Int, ExecutionContext]()

  private def createNewExecutionContext(maxThreads: Int): ExecutionContext = {
    val es = Executors.newFixedThreadPool(maxThreads)
    ExecutionContext.fromExecutorService(es)
  }

  def ctxForInterval(interval: Int): ExecutionContext = {
    ctxMap(interval)
  }

  def initializeUpdateContexts(intervals: Seq[Int], threadsPerIntervalMap: Map[Int,Int], defaultThreadsPerInterval: Int): Unit = {
    intervals.foreach { interval =>
      if (!ctxMap.contains(interval)) {
        val maxThreads = if (threadsPerIntervalMap.contains(interval)) threadsPerIntervalMap(interval) else defaultThreadsPerInterval
        val ec = createNewExecutionContext(maxThreads)
        ctxMap(interval) = ec
        log.info("ExecutionContexts.initializeUpdateContexts: Created ExecutionContext for interval=" + interval +
          " maxThreads=" + maxThreads + " ec.class="+ ec.getClass.getName)
      }
    }
  }
}

