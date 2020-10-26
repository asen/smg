package com.smule.smg.core

import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors}

import akka.actor.ActorSystem
import com.smule.smg.core.IntervalThreadsConfig.PoolType
import javax.inject.{Inject, Singleton}
import play.libs.Akka

import scala.collection.JavaConverters._
import scala.collection.concurrent
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

/**
  * The various execution contexts used by SMG
  */
@Singleton
class SMGExecutionContexts @Inject() (actorSystem: ActorSystem) extends ExecutionContexts {
  /**
    * The default (Akka/Play) context used for Akka message communications
    */
  val defaultCtx: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  /**
    * Context used when executing external rrdtool commands to graph images
    */
  val rrdGraphCtx: ExecutionContext = actorSystem.dispatchers.lookup("akka-contexts.rrd-graph")

  val monitorCtx: ExecutionContext = actorSystem.dispatchers.lookup("akka-contexts.monitor")

  private val log = SMGLogger

  private val ctxMap: TrieMap[Int,ExecutionContext] = new TrieMap[Int, ExecutionContext]()
  private val executorSvcMap: TrieMap[Int,ExecutorService] = new TrieMap[Int, ExecutorService]()
  private var currentConf: Map[Int,IntervalThreadsConfig] = Map()

  private def createNewExecutionContext(conf: IntervalThreadsConfig): ExecutorService = {
    val maxThreads = if (conf.numThreads <= 0)
      Runtime.getRuntime.availableProcessors
    else
      conf.numThreads
    conf.poolType match {
      case PoolType.FIXED => Executors.newFixedThreadPool(maxThreads)
      case PoolType.WORK_STEALING => Executors.newWorkStealingPool(maxThreads)
    }
  }

  def ctxForInterval(interval: Int): ExecutionContext = {
    ctxMap(interval)
  }

  def initializeUpdateContexts(intervals: Map[Int,IntervalThreadsConfig]): Unit = {
    this.synchronized {
      // shutdown and remove old contexts only after ctxMap has the new ones
      val toShutdown = ListBuffer[ExecutorService]()
      val toRemove = (currentConf.keySet -- intervals.keySet).toSeq
      intervals.foreach { case (intvl, conf) =>
        val cur  = currentConf.get(intvl)
        // keep track of current executor svc to be able to shutdown
        if (cur.isDefined && (cur.get != conf)){
          log.warn(s"ExecutionContexts.initializeUpdateContexts: will shut down executor with conf ${cur.get}")
          toShutdown += executorSvcMap(intvl)
        }
        // create the new execution context if non-existing or changed
        if (cur.isEmpty || (cur.get != conf)){
          log.info(s"ExecutionContexts.initializeUpdateContexts: creating execution context with conf ${conf}")
          val ec = createNewExecutionContext(conf)
          executorSvcMap(intvl) = ec
          ctxMap(intvl) = ExecutionContext.fromExecutorService(ec)
        }
      }
      // now kill any unused
      toShutdown.foreach { ecs => ecs.shutdown() }
      toRemove.foreach { rmIntvl =>
        log.warn(s"ExecutionContexts.initializeUpdateContexts: removing executor for obsolete interval $rmIntvl")
        val rmExSvc = executorSvcMap.remove(rmIntvl)
        ctxMap.remove(rmIntvl)
        rmExSvc.get.shutdown()
      }
      currentConf = intervals
    }
  }
}

