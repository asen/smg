package com.smule.smg.monitor

import com.smule.smg.core.{SMGIndex, SMGLogger}
import com.smule.smg.remote.SMGRemote

// "generic"/remote mon state
case class SMGMonStateView(id: String,
                           severity: Double,
                           text: String,
                           isHard: Boolean,
                           isAcked: Boolean,
                           isSilenced: Boolean,
                           silencedUntil: Option[Int],
                           oid: Option[String],
                           pfId: Option[String],
                           parentId: Option[String],
                           aggShowUrlFilter: Option[String],
                           recentStates: Seq[SMGState],
                           errorRepeat: Int,
                           intervals: Seq[Int],
                           remote: SMGRemote
                          ) extends SMGMonState {

  override def alertKey: String = id

  override def getLocalMatchingIndexes: Seq[SMGIndex] = {
    // TODO XXX this should never be called ... throw a runtime error instead? For now just log
    // may have to do some refactoring to fix that
    SMGLogger.error(s"getLocalMatchingIndexes called for SMGMonStateView($id)")
    Seq()
  }
}

