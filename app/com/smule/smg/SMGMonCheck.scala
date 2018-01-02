package com.smule.smg

trait SMGMonCheck {

  def checkValue(ou: SMGObjectUpdate, vix: Int, ts: Int, newVal: Double, checkConf: String): SMGState

}

case class SMGMonCheckConf(ckId: String, conf: String, check: SMGMonCheck)
