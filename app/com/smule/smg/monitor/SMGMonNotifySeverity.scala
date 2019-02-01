package com.smule.smg.monitor

object SMGMonNotifySeverity extends Enumeration {
  val RECOVERY, ACKNOWLEDGEMENT, ANOMALY, WARNING, UNKNOWN, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED = Value

  def fromStateValue(sv: SMGState.Value): SMGMonNotifySeverity.Value = {
    sv match {
      case SMGState.OK => this.RECOVERY
      case SMGState.WARNING => this.WARNING
      case SMGState.CRITICAL => this.CRITICAL
      case SMGState.UNKNOWN => this.UNKNOWN
      case SMGState.ANOMALY => this.ANOMALY
      case _ => this.SMGERR
      //        ACKNOWLEDGEMENT, THROTTLED and UNTHROTTLED are special
    }
  }
}

