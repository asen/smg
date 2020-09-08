package com.smule.smg.core

import com.smule.smg.core.SMGStagedRunCounter.IncResult
import com.smule.smg.rrd.SMGRrd

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * Created by asen on 4/9/17.
  */


/**
  * Keep track of how many objects were processed so far during given interval run and help prevent overlapping runs.
  * We want as little synchronization as possible and use a counter to track the progress of the current run.
  *
  * When there are aggregate objects defined, their updates (using calculated values from cached object values) have
  * to happen after the regular objects have had their fetch commands run. Because of this, currently a SMG run can
  * consist of one or two "stages" - the first stage is counting the regular fetch commands, and the second one (if
  * defined) counting aggregate object updates.
  *
  * In addition we want to run some code at the end of each stage and run - this is the code actually sending the
  * aggregate object update messages (at the end of stage 0) and we also want to do a rrdcached flush at the end
  * of the run.
  *
  * @param interval - the interval this counter counts for. needed only for some log messages
  * @param stageDefs - an array of "stage defs", each representing a max count and a proc to run when that
  *                  count is reached
  */
class SMGStagedRunCounter(interval: Int, stageDefs: Array[SMGRunStageDef]) {

  private val log = SMGLogger

  val resetTss: Int = SMGRrd.tssNow

  private val curPerStage = stageDefs.map(_ => 0)

  private val numStages: Int = stageDefs.length
  private var curStage: Int = 0

  /**
    * Increment the run counter
    * @return - true if a stage was complete (including the last stage which means the run completed)
    *         false otherwise. Note that from all callers only one is guaranteed to get true
    */
  protected def inc(): IncResult = {
    this.synchronized {
      if (curStage < numStages) {
        curPerStage(curStage) += 1
        val newCur = curPerStage(curStage)
        val stageMax = stageDefs(curStage).maxCount
        if ( newCur < stageMax) {
          IncResult(stageCompleted = false)
        } else {
          if (newCur > stageMax) {
            // this should never happen
            log.error(s"SMGStagedRunCounter(interval=$interval, stages=[${stageDefs.map(_.maxCount).mkString(",")}]): " +
              s"stage $curStage count exceded max ($stageMax) - should never happen")
          }
          // stage completed
          stageDefs(curStage).proc()
          curStage += 1
          IncResult(stageCompleted = true, runCompleted = curStage == numStages)
        }
      } else {  // this should never happen
        log.error(s"SMGStagedRunCounter(interval=$interval, stages=[${stageDefs.map(_.maxCount).mkString(",")}]): " +
          s"current stage $curStage exceded max (${numStages - 1})")
        IncResult(stageCompleted = true, runCompleted = true)
      }
    }
  }

  /**
    * Check if all stages were counted (i.e. the run is complete)
    * @return
    */
  protected def checkCompleted: Boolean = {
    val ret = curStage >= numStages
    if (!ret){
      log.error(s"SMGStagedRunCounter(interval=$interval, stages=[${stageDefs.map(_.maxCount).mkString(",")}]): " +
        s"overlap detected: curStage=$curStage, curStageCounts=[${curPerStage.mkString(",")}]")
    }
    ret
  }
}

/**
  * The singleton keeping all per-interval SMGStagedRunCounters in a map.
  */
object SMGStagedRunCounter {

  case class IncResult(stageCompleted: Boolean, runCompleted: Boolean = false)

  private val log = SMGLogger

  private val totalsPerInterval = mutable.Map[Int, SMGStagedRunCounter]()

  private val startTimePerInterval = TrieMap[Int,Long]()
  private val runTimePerInterval = TrieMap[Int,Long]()

  /**
    * Reset the counter for given interval using the supplied stage defs. The reset will fail if the
    * previous run counter has not completed yet, up until 5 * interval at which point the counter is
    * "forcefully" reset
    *
    * @param interval - interval for which to reset the counter
    * @param stageDefs - the stage defs for the new run
    * @return - true if the reset succeeded, false otherwise
    */
  def resetInterval(interval: Int, stageDefs: Array[SMGRunStageDef]): Boolean = {
    totalsPerInterval.synchronized {
      val prev = totalsPerInterval.get(interval)
      if (prev.isEmpty || prev.get.checkCompleted) {
        totalsPerInterval(interval) = new SMGStagedRunCounter(interval, stageDefs)
        startTimePerInterval(interval) = System.currentTimeMillis()
        true
      } else { // prev is defined and not done
        val tsDiff = SMGRrd.tssNow - prev.get.resetTss
        val maxTsDiff = 10 * interval
        if ( tsDiff > maxTsDiff) {
          log.error(s"SMGStagedRunCounter.resetInterval(interval=$interval): " +
            s"Reset did not succeed for $tsDiff seconds (max=$maxTsDiff, prev resetTss=${prev.get.resetTss}): " +
            "forcing reset")
          totalsPerInterval(interval) = new SMGStagedRunCounter(interval, stageDefs)
          val now = System.currentTimeMillis()
          if (startTimePerInterval.contains(interval))
            runTimePerInterval(interval) = startTimePerInterval(interval) - now
          startTimePerInterval(interval) = now
          true
        } else { // "normal" overlap
          false
        }
      }
    }
  }

  /**
    * Increment the interval counter. If this results in run stage completion the associated proc will be executed.
    *
    * @return - true if a stage was completed (including the last stage which means the run completed and false
    *         otherwise.
    */

  def incIntervalCount(interval: Int): Boolean = {
    val ret = totalsPerInterval(interval).inc()
    if ((ret.runCompleted) && (startTimePerInterval.contains(interval))){
      runTimePerInterval(interval) = System.currentTimeMillis() - startTimePerInterval(interval)
    }
    ret.stageCompleted
  }

  def getLastRunTimesPerInterval: Map[Int, Long] = runTimePerInterval.toMap
}
