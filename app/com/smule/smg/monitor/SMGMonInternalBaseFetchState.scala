package com.smule.smg.monitor

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGObjectUpdate

trait SMGMonInternalBaseFetchState extends SMGMonInternalState {

  def processError(ts: Int, exitCode :Int, errors: List[String], isInherited: Boolean): Unit = {
    val errorMsg = s"Fetch error: exit=$exitCode, OUTPUT: " + errors.mkString("\n")
    addState(SMGState(ts, SMGState.UNKNOWN, errorMsg), isInherited)
  }

  def processSuccess(ts: Int, isInherited: Boolean): Unit = {
    addState(SMGState(ts, SMGState.OK, "OK"), isInherited)
  }

  protected def pfNotifyCmdsAndBackoff(myConfigSvc: SMGConfigService,
                                       myNotifyConf: Option[SMGMonNotifyConf],
                                       ous: Seq[SMGObjectUpdate]
                                      ): (Seq[SMGMonNotifyCmd], Int) = {
    if (myNotifyConf.isDefined) {
      val ncmds = if (myNotifyConf.get.notifyDisable)
        Seq()
      else
        myNotifyConf.get.unkn.map(s => myConfigSvc.config.notifyCommands.get(s)).filter(_.isDefined).map(_.get)
      val backoff = myNotifyConf.get.notifyBackoff.getOrElse(myConfigSvc.config.globalNotifyBackoff)
      (ncmds, backoff)
    } else {
      val tuples = ous.
        map(ou => myConfigSvc.objectVarNotifyCmdsAndBackoff(ou, None, SMGMonNotifySeverity.UNKNOWN))
      val backoff = tuples.map(_._2).max
      val ncmds = tuples.flatMap(_._1).distinct
      (ncmds, backoff)
    }
  }

  protected def pfMaxHardErrorCount(myConfigSvc: SMGConfigService,
                                    myNotifyConf: Option[SMGMonNotifyConf],
                                    ous: Seq[SMGObjectUpdate]): Int = {
    if (myNotifyConf.isDefined) {
      myNotifyConf.get.notifyStrikes.getOrElse(myConfigSvc.config.globalNotifyStrikes)
    } else {
      if (ous.isEmpty) {
        myConfigSvc.config.globalNotifyStrikes
      } else {
        ous.map { ou => myConfigSvc.objectVarNotifyStrikes(ou, None) }.min
      }
    }
  }
}
