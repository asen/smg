package com.smule.smg.notify

/**
  * Created by asen on 8/30/16.
  */

case class SMGMonNotifyConfObj(varConfs: Map[Int, Seq[SMGMonNotifyConf]]) {

  def varConf(ix: Int): Seq[SMGMonNotifyConf] = varConfs.getOrElse(ix, Seq())

  def getIsDisabledAndBackoff(ix: Int): (Boolean, Option[Int]) = {
    val confs = varConf(ix)
    val isDisabled = confs.nonEmpty && confs.exists(_.notifyDisable)
    val backoffs = confs.map(_.notifyBackoff)
    if (confs.nonEmpty)
      (isDisabled, backoffs.max) // TODO? XXX longer backoff period overrides conflicting shorter backoff period
    else (isDisabled, None)
  }
}
