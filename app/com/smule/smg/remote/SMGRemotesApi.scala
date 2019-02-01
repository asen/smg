package com.smule.smg.remote

import com.smule.smg.core._
import com.smule.smg.grapher.{GraphOptions, SMGAggObjectView, SMGImageView}
import com.smule.smg.monitor._
import com.smule.smg.rrd.{SMGRrdFetchParams, SMGRrdRow}

import scala.concurrent.Future

/**
  * Remotes API interface (to be injected by Guice)
  */
trait SMGRemotesApi {

  /**
    * Reload the local representation of all remote configs and cache them locally
    */
  def fetchConfigs(): Unit

  /**
    * Reload the local representation of specific remote config and cache locally
    * @param slaveId
    */
  def fetchSlaveConfig(slaveId: String): Unit

  /**
    * Invoke a remote API call to all slave remotes, asking them to reload their configs
    * This may be deprecated in teh future
    */
  def notifySlaves(): Unit

  /**
    * Notify all master configs to refresh this instance config
    */
  def notifyMasters(): Unit

  /**
    * Get all currently cached remote configs
    * @return - sequence of remote configs  - one per remote
    */
  def configs: Seq[SMGRemoteConfig]

  /**
    * Get the cached config for a remote with given id
    * @param id - id to lookup
    * @return - Some config if available, None otherwise.
    */
  def byId(id: String): Option[SMGRemoteConfig]

  /**
    * An asynchronous call to graph a list of objects for a list of periods. The objects are grouped by remote
    * and requests are sent to the respective remotes to produce the images.
    * @param lst - list of remote objects to graph
    * @param periods - list of periods to cover
    * @return - a Future sequence of graphed images (possibly from multiple remotes)
    */
  def graphObjects(lst: Seq[SMGObjectView], periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]]

  /**
    * An asynchronous call to graph an aggregate object for a list of periods. The aggregate object children
    * must be from the given remote (others would be ignored).
    * @param remoteId - remote id to request the images from
    * @param aobj - aggregate object
    * @param periods - list of peridos to cover
    * @return - a Future list of aggregate images (one per period)
    */
  def graphAgg(remoteId: String, aobj: SMGAggObjectView, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]]

  /**
    * fetch csv data  for a non-Agg object from remote instance
    * @param remoteOid - remote object id
    * @param params - fetch params
    * @return - future sequence of rrd rows data
    */
  def fetchRows(remoteOid: String, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]]

  /**
    * fetch csv data  for a multiple non-Agg object from (possibly multiple) remote instances
    * @param remoteOids - remote object id
    * @param params - fetch params
    * @return - future sequence of rrd rows data
    */
  def fetchRowsMany(remoteOids: Seq[String], params: SMGRrdFetchParams): Future[Seq[(String,Seq[SMGRrdRow])]]

  /**
    * fetch csv data  for an Agg object from remote instance
    * @param aobj - agg object instance
    * @param params - fetch params
    * @return - future sequence of rrd rows data
    */
  def fetchAggRows(aobj: SMGAggObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]]

  /**
    * Download rrd file from remote instance (to use for cross-remote agg images)
    * @param robj
    * @return
    */
  def downloadRrd(robj:SMGObjectView): Future[Option[SMGObjectView]]

  /**
    * Request plugin data from remote instance
    * @param remoteId - id of the remote to get data from
    * @param pluginId - relevant plugin id
    * @param httpParams - http params to pass
    * @return - the remote plugin response body
    */
  def pluginData(remoteId: String, pluginId: String, httpParams: Map[String, String]): Future[String]


  //Monitoring
  /**
    * Remote call to get all state objects for given sequence of object views (preserving order)
    * @param remoteId - id of the remote to get data from
    * @param ovs - sequence of object views for which to get mon states
    * @return - async sequence of mon states
    */
  def objectViewsStates(remoteId: String, ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonState]]]

  /**
    * Get all monitor logs matching the filter from the given remote
    * @param remoteId - id of the remote to get data from
    * @param flt - filter
    * @return
    */
  def monitorLogs(remoteId: String, flt: SMGMonitorLogFilter): Future[Seq[SMGMonitorLogMsg]]

  /**
    * Request heatmap from the given remote. A heatmap is (possibly condensed) list of SMGMonState squares.
    * @param remoteId - id of the remote to get data from
    * @param flt - filter to use to get objects
    * @param maxSize - limit the heatmap to that many squares (note max width is enforced separately).
    * @param offset - offset in the filtered objects list to start the heatmap from
    * @param limit - limit the number of filtered objects to include
    * @return
    */
  def heatmap(remoteId: String, flt: SMGFilter, ix: Option[SMGIndex], maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[SMGMonHeatmap]


  /**
    * Request run (command) trees data from the given remote
    * @param remoteId - id of remote
    * @param root - optional "root" id
    * @return
    */
  def monitorRunTree(remoteId: String, root: Option[String]): Future[Map[Int,Seq[SMGFetchCommandTree]]]

  /**
    * remote call to get current (problem) states
    * @param remote
    * @param flt
    * @return
    */
  def monitorStates(remote: SMGRemote, flt: SMGMonFilter): Future[SMGMonitorStatesResponse]

  /**
    * remote call to get all currently silenced states
    * @param remoteId
    * @return
    */
  def monitorSilenced(remoteId: String): Future[(Seq[SMGMonState], Seq[SMGMonStickySilence])]

  def removeStickySilence(remoteUid: String): Future[Boolean]

  /**
    * remote call to get a page of monitor state trees items
    * @param remoteId
    * @param flt
    * @param rootId
    * @param limit
    * @return
    */
  def monitorTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String], limit: Int): Future[(Seq[SMGTree[SMGMonState]], Int)]

  def monitorSilenceAllTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String], until: Int,
                             sticky: Boolean, stickyDesc: Option[String]): Future[Boolean]

  def monitorMute(remoteId: String): Future[Boolean]

  def monitorUnmute(remoteId: String): Future[Boolean]

  /**
    * remote call to acknowledge an error for given monitor state
    * @param id - monitor state id
    * @return
    */
  def monitorAck(id: String): Future[Boolean]

  /**
    * remote call to un-acknowledge an acknowledged error for given monitor state
    * @param id - monitor state id
    * @return
    */
  def monitorUnack(id: String): Future[Boolean]

  /**
    * remote call to silence a monitor state until given time in the future
    * @param id
    * @param slunt
    * @return
    */
  def monitorSilence(id: String, slunt: Int): Future[Boolean]

  /**
    * remote call to unsilence an silenced monitor state
    * @param id - monitor state id
    * @return
    */
  def monitorUnsilence(id: String): Future[Boolean]

  /**
    * Acknowledge an error for given monitor states. Acknowledgement is automatically cleared on recovery.
    * @param ids
    * @return
    */
  def acknowledgeList(remoteId: String, ids: Seq[String]): Future[Boolean]

  /**
    * Silence given states for given time period
    * @param ids
    * @param slunt
    * @return
    */
  def silenceList(remoteId: String, ids: Seq[String], slunt: Int): Future[Boolean]

}


