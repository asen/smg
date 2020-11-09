package com.smule.smg.notify

import com.smule.smg.monitor.SMGState

object SMGMonNotifySeverity extends Enumeration {
  val RECOVERY, ACKNOWLEDGEMENT, ANOMALY, WARNING, FAILED, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED = Value

  def fromStateValue(sv: SMGState.Value): SMGMonNotifySeverity.Value = {
    sv match {
      case SMGState.OK => this.RECOVERY
      case SMGState.WARNING => this.WARNING
      case SMGState.CRITICAL => this.CRITICAL
      case SMGState.FAILED => this.FAILED
      case SMGState.ANOMALY => this.ANOMALY
      case _ => this.SMGERR
      //        ACKNOWLEDGEMENT, THROTTLED and UNTHROTTLED are special
    }
  }
}
