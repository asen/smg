package com.smule.smg.config

import com.smule.smg.core.SMGLogger

import java.util.concurrent.atomic.AtomicInteger

class ProtectedReloadObj(owner: String) {

  private val log = SMGLogger

  private val pendingReloads = new AtomicInteger(0)
  private val MAX_RELOADS_IN_A_ROW = 10

  def reloadOrQueue(doReloadSync: () => Unit): Boolean = {
    var pending = pendingReloads.incrementAndGet()
    if (pending > 1){
      log.warn(s"${owner}.reload: Reload already running, requested another one  (pending=$pending)")
      return false
    }
    // only one thread which got pending=1 gets here.
    // pendingReloads does not get to 0 or 1 until done and before then can only be incremented
    var reloads = 0
    while (pending > 0) {
      reloads += 1
      log.info(s"${owner}.reload: Reload requested (reloads=$reloads/pending=${pendingReloads.get()})")
      doReloadSync()
      pending = pendingReloads.decrementAndGet()
      // if pending == 0 (was 1) -> all good, new threads can take over
      // if pending == 1 (was 2) -> this thread will do another reload, others can't take over
      if (pending > 1) { // consolidate more than 1 reload requests into one.
        log.warn(s"${owner}.reload: Consolidating multiple pending reload requests into one ($pending)")
        pendingReloads.set(1)
        pending = 1
      }
      if (reloads > MAX_RELOADS_IN_A_ROW && pending > 0) {
        log.error(s"${owner}.reload: Too many reloads in a row: (reloads=$reloads/pending=${pendingReloads.get()})")
        pending = 0
      }
    }
    return true
  }

}
