package com.smule.smg.monitor

import scala.concurrent.Future

trait SMGMonitorLogApi {
  /**
    * log a state change
    * @param msg - msg representing the state change
    */
  def logMsg(msg: SMGMonitorLogMsg): Unit

  /**
    * Get all local logs matching filter
    * @param flt - filter object
    * @return
    */
  def getLocal(flt: SMGMonitorLogFilter): Seq[SMGMonitorLogMsg]

  /**
    * Get all logs matching the filter (from all remotes)
    * @param flt
    * @return
    */
  def getAll(flt: SMGMonitorLogFilter): Future[Seq[SMGMonitorLogMsg]]

  /**
    * Called by scheduler to flush logs to disc asynchronously
    */
  def tick():Unit

}
