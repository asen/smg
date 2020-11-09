package com.smule.smg.notify

case class SMGMonThrottledMsgData(
                                   severity: SMGMonNotifySeverity.Value,
                                   alertKey: String,
                                   subjStr: String
                                 )
