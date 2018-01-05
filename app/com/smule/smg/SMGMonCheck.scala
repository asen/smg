package com.smule.smg

/**
  * Plugins can extend SMG monitor check capabilities by providing (labelled) implementations of this trait.
  */
trait SMGMonCheck {

  /**
    * The actual check interface
    * @param ou - the rrd/update object being checked
    * @param vix - the variable index
    * @param ts - the timestamp at which the new value was fetched
    * @param newVal - the new value for the variable
    * @param checkConf - the check config passed to alert-p-...:
    * @return
    */
  def checkValue(ou: SMGObjectUpdate, vix: Int, ts: Int, newVal: Double, checkConf: String): SMGState

  /*
    * Override this to provide meaningful state inspect
    */
  def inspectState(ou: SMGObjectUpdate, vix: Int, checkConf: String): String =
    s"inspectState: (${ou.id}, $vix, $checkConf)"
}

case class SMGMonCheckConf(ckId: String, conf: String, check: SMGMonCheck)
