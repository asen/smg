package com.smule.smg.monitor

import com.smule.smg.core.SMGIndex
import com.smule.smg.remote.SMGRemote


// local agg (condensed) mon state
case class SMGMonStateAgg(id: String, lst: Seq[SMGMonState], showUrlFilter: String) extends SMGMonState {

  lazy val lstsz: Int = lst.size

  override val severity: Double =  if (lstsz == 0) 0 else lst.map(_.severity).max
  override def text: String = {
    val lsz = lst.size
    if (lsz > 10)
      s"Multiple ($lsz) objects including: \n" + lst.take(5).map(ms => ms.text + ms.hardStr).mkString("\n") + " ..."
    else
      lst.map(ms => ms.text + ms.hardStr).mkString("\n")
  }
  override val isHard: Boolean = lst.exists(_.isHard)             // at least one hard means hard
  override val isAcked: Boolean = lst.forall(_.isAcked)
  override val isSilenced: Boolean = lst.forall(_.isSilenced)
  private val minSilencedUntil = lst.map(ovs => ovs.silencedUntil.getOrElse(Int.MaxValue)).min
  override val silencedUntil: Option[Int] = if (minSilencedUntil == Int.MaxValue) None else Some(minSilencedUntil)
  override val oid: Option[String] = None
  override val pfId: Option[String] = None
  override val aggShowUrlFilter = Some(showUrlFilter)

  override val errorRepeat: Int = lst.map(_.errorRepeat).max
  //  override lazy val hardStr = ""
  override val remote: SMGRemote = SMGRemote.local

  override val recentStates: Seq[SMGState] = {
    val longestListState = lst.maxBy(_.recentStates.size)
    longestListState.recentStates.indices.map { i =>
      val statesAtI = lst.map(ms => ms.recentStates.lift(i)).collect { case Some(x) => x }
      statesAtI.maxBy(_.state)
    }
  }

  override val intervals: Seq[Int] = lst.flatMap(_.intervals).distinct.sorted

  // XXX chop off :ix portion of child alert keys to define this alert key.
  // Agg states cover entire objects in the context of alerting so var indexes are thrown away
  override def alertKey: String = lst.map(_.alertKey.split(":")(0)).distinct.mkString(",")

  override def parentId: Option[String] = None

  override def getLocalMatchingIndexes: Seq[SMGIndex] =
    lst.flatMap(_.getLocalMatchingIndexes ).sortBy(_.title).distinct
}

object SMGMonStateAgg {

  def objectsUrlFilter(oids: Seq[String]): String = {
    if (oids.isEmpty)
      return "rx=ERROR"
    if (oids.tail.isEmpty)
      return SMGMonState.oidFilter(oids.head)
    val rx = s"^(${oids.map(oid => SMGRemote.localId(oid)).distinct.mkString("|")})$$"
    "rx=" + java.net.URLEncoder.encode(rx, "UTF-8") // TODO, better showUrl?
  }

  def aggByParentId(lst: Seq[SMGMonState]): Map[String, SMGMonState]= {
    val ret = lst.filter(_.parentId.isDefined).groupBy(_.parentId.get).map { t =>
      val pid = t._1
      val msa = SMGMonStateAgg(pid, t._2, s"prx=^${pid}$$")
      (pid,msa)
    }
    ret
  }
}
