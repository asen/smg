package com.smule.smg

import scala.concurrent.Future


/**
  * Created by asen on 7/6/16.
  */

case class SMGMonitorStatesResponse(remote: SMGRemote, states: Seq[SMGMonState], isMuted: Boolean)

trait SMGMonitorApi {

  /**
    * Get all state objects for given sequence of object views
    * @param ovs - sequence of object views for which to get mon states
    * @return - async map of object view ids -> sequence of mon states
    */
  def objectViewStates(ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonState]]]


  /**
    * Get all matching states for the given filter
    * @param flt - the filter
    * @return
    */
  def localStates(flt: SMGMonFilter, includeInherited: Boolean): Seq[SMGMonState]
  
  /**
    * Get all states matching given filter, by remote
    * @param remoteIds - when empty - return matching states from all remotes
    * @param flt - the filter
    * @return
    */
  def states(remoteIds: Seq[String], flt: SMGMonFilter): Future[Seq[SMGMonitorStatesResponse]]

  def mute(remoteId: String): Future[Boolean]

  def unmute(remoteId: String): Future[Boolean]

  /**
    * Return all local silenced states
    * @return
    */
  def localSilencedStates(): (Seq[SMGMonState], Seq[SMGMonStickySilence])

  /**
    * Return all currently silenced states (by remote)
    * @return
    */
  def silencedStates(): Future[Seq[(SMGRemote, Seq[SMGMonState], Seq[SMGMonStickySilence])]]

  /**
    *
    * @param flt
    * @param rootId
    * @return
    */
  def localMatchingMonTrees(flt: SMGMonFilter, rootId: Option[String]): Seq[SMGTree[SMGMonInternalState]]

  /**
    *
    * @param remoteIds
    * @param flt
    * @param rootId
    * @param limit
    * @return a tuple with the resulting page of trees and the total number of pages
    */
  def monTrees(remoteIds: Seq[String], flt: SMGMonFilter, rootId: Option[String], limit: Int): Future[(Seq[SMGTree[SMGMonState]], Int)]

  /**
    *
    * @param remoteIds
    * @param flt
    * @param rootId
    * @param until
    * @param sticky
    * @param stickyDesc
    * @return
    */
  def silenceAllTrees(remoteIds: Seq[String], flt: SMGMonFilter, rootId: Option[String], until: Int,
                      sticky: Boolean, stickyDesc: Option[String]): Future[Boolean]

  def removeStickySilence(uid: String): Future[Boolean]

  /**
    * Acknowledge an error for given monitor state. Acknowledgement is automatically cleared on recovery.
    * @param id
    * @return
    */
  def acknowledge(id: String): Future[Boolean]

  /**
    * Un-acknowledge previously acknowledged error
    * @param id
    * @return
    */
  def unacknowledge(id: String): Future[Boolean]

  /**
    * Silence given state for given time period
    * @param id
    * @param slunt
    * @return
    */
  def silence(id: String, slunt: Int): Future[Boolean]

  /**
    * Unsilence previously silenced state.
    * @param id
    * @return
    */
  def unsilence(id: String): Future[Boolean]

  /**
    * Acknowledge an error for given monitor states. Acknowledgement is automatically cleared on recovery.
    * @param ids
    * @return
    */
  def acknowledgeList(ids: Seq[String]): Future[Boolean]

  /**
    * Silence given states for given time period
    * @param ids
    * @param slunt
    * @return
    */
  def silenceList(ids: Seq[String], slunt: Int): Future[Boolean]

  /**
    * Acknowledge an error for given monitor states. Acknowledgement is automatically cleared on recovery.
    * @param ids
    * @return
    */
  def acknowledgeListLocal(ids: Seq[String]): Boolean

  /**
    * Silence given states for given time period
    * @param ids
    * @param slunt
    * @return
    */
  def silenceListLocal(ids: Seq[String], slunt: Int): Boolean


  /**
    * Generate a heatmap from local for the system objects. A heatmap is (possibly condensed) list of SMGMonState squares.
    * @param flt - filter to use to get objects
    * @param maxSize - limit the heatmap to that many squares (note max width is enforced separately).
    * @param offset - offset in the filtered objects list to start the heatmap from
    * @param limit - limit the number of filtered objects to include
    * @return
    */
  def localHeatmap(flt: SMGFilter, ix: Option[SMGIndex], maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): SMGMonHeatmap

  /**
    * Generate a sequence of heatmaps (by remote). A heatmap is (possibly condensed) list of SMGMonState squares.
    * @param flt - filter to use to get objects
    * @param maxSize - limit the heatmap to that many squares (note max width is enforced separately).
    * @param offset - offset in the filtered objects list to start the heatmap from
    * @param limit - limit the number of filtered objects to include
    * @return  - sequence of (remote,heatmap) tuples
    */
  def heatmap(flt: SMGFilter, ix: Option[SMGIndex], maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[Seq[(SMGRemote, SMGMonHeatmap)]]

  def saveStateToDisk(): Unit

  /**
    * a convenience reference to the SMGMonitorLogApi
    */
  val monLogApi: SMGMonitorLogApi


  def inspectObject(ov:SMGObjectView): Option[String]
  def inspectPf(pfId: String): Option[String]

}
