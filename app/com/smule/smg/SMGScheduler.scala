package com.smule.smg

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Created by asen on 11/17/15.
  */

/**
  * Dummy interface to be able to inject the scheduler as GUICE dependency
  */
trait SMGSchedulerApi {
}

/**
  * The SMG internal scheduler class. It runs a "tick" every X seconds and runs updates for mathcing intervals.
  * X is calculated as half of the greatest common divisor of all intervals, ensuring that a tick will run at least once
  * in half of the shortest interval but also match all intervals at some point. On startup, the initial tick is aligned
  * to the next timestamp for which ts % X = 0. E.g. if the shortest interval is 1 min, it will run a tick every 30
  * seconds - at :00 and :30.
  *
  * @param configSvc - SMG configuration service
  * @param smg - smg API instance
  * @param system - actor system instance
  * @param lifecycle - Play Applicaion lifecycle
  */
@Singleton
class SMGScheduler @Inject() (configSvc: SMGConfigService,
                              smg: GrapherApi,
                              monitorApi: SMGMonitorApi,
                              notifyApi: SMGMonNotifyApi,
                              system: ActorSystem, lifecycle: ApplicationLifecycle) extends SMGSchedulerApi {
  val log = SMGLogger

  val MIN_INTERVAL = 15

  val MAX_TICK_JITTER = 15

  val isShuttingDown = new AtomicBoolean(false)

  lifecycle.addStopHook( () => Future { isShuttingDown.set(true) } )

  if (configSvc.useInternalScheduler)
    start()
  else
    log.info("SMGScheduler: useInternalScheduler is disabled, please configure any necessary cron jobs")


  private def calcTickInterval(intervals: Seq[Int]): Int  = {
    if (intervals.isEmpty){
      log.error("SMGScheduler.start: Config contains no intervals - aborting. Please update config and call reload")
      MIN_INTERVAL
    } else {
      val ret = intervals.tail.foldLeft[Int](intervals.head) { (prev: Int, cur: Int) => BigInt(cur).gcd(prev).toInt }
      if (ret < MIN_INTERVAL) {
        log.error("SMGScheduler.calcTickInterval: refusing to work with interval of less than " + MIN_INTERVAL + " seconds (" + ret + ") fall-back to 10 seconds")
        MIN_INTERVAL
      } else if (ret % 2 == 0) {
        // use half the interval for improved accuracy
        ret / 2
      } else {
        log.warn("SMGScheduler.calcTickInterval: calculated odd tick interval " + ret)
        ret
      }
    }
  }

  private def timeToNextTick(tickIntvl: Int) = {
    (tickIntvl - SMGRrd.tssNow % tickIntvl).seconds
  }

  private def getSchedulerIntervals = configSvc.config.intervals.filter(_ > 0).toSeq.sorted

  private def tick(): Unit = {
    // do this first
    val tickTsSecs = SMGRrd.tssNow
    if (isShuttingDown.get() || system.isTerminated) {
      log.info("SMGScheduler.tick: (shutting down) " + tickTsSecs)
      return
    }
    log.info("SMGScheduler.tick: " + tickTsSecs)
    val curIntervals = getSchedulerIntervals
    val curTickInterval = calcTickInterval(curIntervals)
    for (i <- curIntervals) {
      if (tickTsSecs % i < MAX_TICK_JITTER) { // allow it to be up to MAX_TICK_JITTER second(s) late
        try {
          smg.run(i)
        } catch {
          case t: Throwable => log.ex(t, "Unexpected exception from smg.run(" + i +")")
        }
      }
    }
    Future { // run these async
      notifyApi.tick()
      monitorApi.monLogApi.tick()
    } (ExecutionContexts.monitorCtx)
    system.scheduler.scheduleOnce(timeToNextTick(curTickInterval))(tick())
  }

  def start(): Unit = {
    val startTickInterval = calcTickInterval(getSchedulerIntervals)
    log.info("SMGScheduler.start initializing with tickInterval=" + startTickInterval)
    system.scheduler.scheduleOnce(timeToNextTick(startTickInterval))(tick())
  }

}
