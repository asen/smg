package com.smule.smg.notify

import scala.collection.mutable.ListBuffer

class SMGMonThrottledRecipientState {
  var currentThrottleTs: Int = 0
  var currentThrottleCnt: Int = 0
  val throttledMsgs: ListBuffer[SMGMonThrottledMsgData] = ListBuffer[SMGMonThrottledMsgData]()
  var throttledIsSent: Boolean = false
}
