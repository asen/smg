package com.smule.smg.notify

import com.smule.smg.core.SMGCmd

case class SMGMonNotifyCmd(id: String, command: String, timeoutSec: Int) {

  def alert(severity: SMGMonNotifySeverity.Value, alertKey: String, subjStr: String, bodyStr: String): Unit = {
    val myEnv = Map(
      "SMG_ALERT_SEVERITY" -> severity.toString,
      "SMG_ALERT_KEY" -> alertKey,
      "SMG_ALERT_SUBJECT" -> subjStr,
      "SMG_ALERT_BODY" -> bodyStr
    )
    SMGCmd.runCommand(command, timeoutSec, myEnv)
  }
}
