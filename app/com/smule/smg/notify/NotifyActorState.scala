package com.smule.smg.notify

import scala.collection.concurrent.TrieMap

// all the state we care about and may want to save on restart
class NotifyActorState {
  var isMuted: Boolean = false
  // alert Key -> (cmd id -> Last success ts)
  val activeAlertsLastTs: TrieMap[String, TrieMap[String, Int]] =
    TrieMap[String,TrieMap[String,Int]]()
  // cmd id -> throttle data - NOT serialized
  val throttles: TrieMap[String, SMGMonThrottledRecipientState] =
    TrieMap[String, SMGMonThrottledRecipientState]()
}
