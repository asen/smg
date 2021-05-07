package com.smule.smg.monitor

import com.smule.smg.core.SMGIndex
import com.smule.smg.remote.SMGRemote

case class SMGMonStateGlobal(title: String,
                             label: String,
                             currentState: SMGState
                            ) extends SMGMonState{

  //  def currentState = recentStates.head
  val id: String = label

  val desc = s"$title ($label)"

  def longDesc(s: SMGState) = s"$desc: ${s.desc} (ts=${s.timeStr})"

  lazy val severity: Double = currentState.state.id.toDouble
  def text: String = longDesc(currentState)
  lazy val isHard = true
  override val isAcked = false // TODO
  override val isSilenced = false // TODO
  override val silencedUntil: Option[Int] = None
  override val aggShowUrlFilter: Option[String] = None
  override val oid: Option[String] = None
  override val pfId: Option[String] = None

  override val remote: SMGRemote = SMGRemote.local

  override val errorRepeat  = 1

  override def intervals: Seq[Int] = Seq()

  override def alertKey = s"${SMGMonState.MON_STATE_GLOBAL_PX}$label"

  override def recentStates: Seq[SMGState] = Seq(currentState)

  override def parentId: Option[String] = None

  override def getLocalMatchingIndexes: Seq[SMGIndex] = {
    Seq()
  }
}

