package com.smule.smg.core

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import com.smule.smg.config.SMGConfigService
import com.smule.smg.monitor.SMGMonitorApi
import com.smule.smg.rrd.SMGRrd
import com.smule.smg.GrapherApi
import com.smule.smg.notify.SMGMonNotifyApi
import javax.inject.{Inject, Singleton}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
/**
  * Created by asen on 11/17/15.
  */


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
  private val log = SMGLogger

  val MIN_INTERVAL = 1

  val MAX_TICK_JITTER = 15

  val isShuttingDown = new AtomicBoolean(false)

  lifecycle.addStopHook( () => Future { isShuttingDown.set(true) } )

  if (configSvc.useInternalScheduler)
    start()
  else
    log.info("SMGScheduler: useInternalScheduler is disabled, please configure any necessary cron jobs")


  private def calcTickInterval(intervals: Seq[Int]): Int  = {
    try {
      if (intervals.isEmpty) {
        log.error("SMGScheduler.start: Config contains no intervals - aborting. Please update config and call reload")
        MIN_INTERVAL
      } else {
        val ret = intervals.tail.foldLeft[Int](intervals.head) { (prev: Int, cur: Int) => BigInt(cur).gcd(prev).toInt }
        if (ret < MIN_INTERVAL) {
          log.error("SMGScheduler.calcTickInterval: refusing to work with interval of less than " +
            MIN_INTERVAL + " second(s) (" + ret + s") fall-back to $MIN_INTERVAL seconds")
          MIN_INTERVAL
        } else if (ret % 2 == 0) {
          // use half the interval for improved accuracy
          ret / 2
        } else {
          //log.warn("SMGScheduler.calcTickInterval: calculated odd tick interval " + ret)
          ret
        }
      }
    } catch {
      case t: Throwable =>
        log.ex(t, s"Unexpected exception from calcTickInterval($intervals)")
        MIN_INTERVAL
    }
  }

  private def timeToNextTick(tickIntvl: Int) = {
    val timeToNext = tickIntvl - SMGRrd.tssNow % tickIntvl
    val ret = if (timeToNext > 0) timeToNext else 1
    ret.seconds
  }

  private def getSchedulerIntervals = configSvc.config.intervals.filter(_ > 0).toSeq.sorted

  private def monitorTick(): Unit = {
    try {
      notifyApi.tick()
      monitorApi.monLogApi.tick()
    } catch {
      case t: Throwable => log.ex(t, "Unexpected exception in monitorTick()")
    }
  }

  private def tick(): Unit = {
    // do this first
    val tickTsSecs = SMGRrd.tssNow
    if (isShuttingDown.get()) {
      log.info("SMGScheduler.tick: (shutting down) " + tickTsSecs)
      return
    }
    val curIntervals = getSchedulerIntervals
    val curTickInterval = calcTickInterval(curIntervals)
    log.info(s"SMGScheduler.tick: $tickTsSecs: tickIntvl=$curTickInterval intvls=${curIntervals.mkString(",")}")
    try {
      for (i <- curIntervals) {
        if (tickTsSecs % i < MAX_TICK_JITTER) { // allow it to be up to MAX_TICK_JITTER second(s) late or early
          try {
            smg.run(i)
          } catch {
            case t: Throwable => log.ex(t, "Unexpected exception from smg.run(" + i + ")")
          }
        }
      }
      Future { // run this async
        monitorTick()
      }(configSvc.executionContexts.monitorCtx)
    } finally {
      system.scheduler.scheduleOnce(timeToNextTick(curTickInterval))(tick())
    }
  }

  def start(): Unit = {
    val startTickInterval = calcTickInterval(getSchedulerIntervals)
    log.info("SMGScheduler.start initializing with tickInterval=" + startTickInterval)
    system.scheduler.scheduleOnce(timeToNextTick(startTickInterval))(tick())
  }

}
