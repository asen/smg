package com.smule.smg

import java.io.File
import javax.inject.{Inject, Singleton}

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.WSClient

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/**
  * Created by asen on 11/19/15.
  */

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
    * Get all monitor logs since given period from the given remote
    * @param remoteId - id of the remote to get data from
    * @param periodStr - period string
    * @param limit - max entries to return
    * @param inclSoft - whether to include soft errors or hard only
    * @param inclAcked- whether to include acked errors
    * @param inclSilenced - whether to include silenced errors
    * @return
    */
  def monitorLogs(remoteId: String, periodStr: String, limit: Int,
                  minSeverity: Option[SMGState.Value], inclSoft: Boolean,
                  inclAcked: Boolean, inclSilenced: Boolean): Future[Seq[SMGMonitorLogMsg]]

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
  def monitorSilencedStates(remoteId: String): Future[Seq[SMGMonState]]

  /**
    * remote call to get a page of monitor state trees items
    * @param remoteId
    * @param flt
    * @param rootId
    * @param pg
    * @param pgSz
    * @return
    */
  def monitorTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String], pg: Int, pgSz: Int): Future[(Seq[SMGTree[SMGMonState]], Int)]

  def monitorSilenceAllTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String], until: Int): Future[Boolean]

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


/**
  * SMGRemotesApi Singleton implementation to be injected by Guice
  * @param configSvc - SMG configuration service
  * @param ws - Play Web Services client
  */
@Singleton
class SMGRemotes @Inject() ( configSvc: SMGConfigService, ws: WSClient) extends SMGRemotesApi {

  private val log = SMGLogger

  private val remoteClients = TrieMap[String,SMGRemoteClient]()
  private val remoteMasterClients = TrieMap[String,SMGRemoteClient]()

  private val cachedConfigs = TrieMap[String,Option[SMGRemoteConfig]]()

  private def rrdCacheBaseDir = {
    configSvc.config.globals.getOrElse("$rrd_cache_dir", "smgrrd")
  }

  override def notifySlaves(): Unit = {
    remoteClients.foreach { kv =>
      kv._2.notifyReloadConf()
    }
  }

  private def initClients(remotes: Seq[SMGRemote], tgt: TrieMap[String,SMGRemoteClient], logType: String) = {
    remotes.foreach { rmt =>
      val oldCliOpt = tgt.get(rmt.id)
      if (oldCliOpt.isEmpty || (oldCliOpt.get.remote != rmt)) //second part is to cover changed url
        tgt(rmt.id) = new SMGRemoteClient(rmt, ws, configSvc)
    }
    //also remove obsolete clients
    tgt.keys.toList.foreach { k =>
      if (!remotes.exists(_.id == k)) {
        log.warn(s"Removing obsolete client for remote $k")
        tgt.remove(k)
      }
    }
    log.info(s"SMGRemotes.initClients($logType): clients.size=${tgt.size}")
  }

  private def initRemoteClients(): Unit = {
    initClients(configSvc.config.remotes, remoteClients, "slaves")
  }

  /**
    * @inheritdoc
    */
  override def fetchConfigs(): Unit = {
    val configRemotes = configSvc.config.remotes
    initRemoteClients()
    cachedConfigs.keys.toList.foreach { k =>
      if (!configRemotes.exists(_.id == k)) {
        log.warn(s"Removing obsolete config for remote $k")
        cachedConfigs.remove(k)
      }
    }
    val futs = ListBuffer[Future[Boolean]]()
    remoteClients.toMap.foreach { t =>
      val rmtId = t._1
      val cli = t._2
      futs += cli.fetchConfig.map { orc =>
        orc match {
          case Some(rc) => log.info("SMGRemotes.fetchConfigs: received config from " + rc.remote.id)
          case None => log.warn("SMGRemotes.fetchConfigs: failed to receive config from " + cli.remote.id)
        }
        cachedConfigs(t._1) = orc
        true
      }
    }
    Future.sequence(futs.toList).map { bools =>
      if (bools.exists(x => x))
        configSvc.notifyReloadListeners("SMGRemotes.fetchConfigs")
    }
  }

  // don't forget to reload initially
  fetchConfigs()

  // TODO need to rethink dependencies (who creates the plugins) to get rid of this
  configSvc.plugins.foreach(_.setRemotesApi(this))

  override def fetchSlaveConfig(slaveId: String): Unit = {
    initRemoteClients()
    val cli = remoteClients.get(slaveId)
    if (cli.isDefined){
      cli.get.fetchConfig.map { copt =>
        if(copt.isDefined) cachedConfigs(slaveId) = copt
        configSvc.notifyReloadListeners(s"SMGRemotes.fetchConfigs($slaveId)")
      }
    } else {
      log.warn(s"SMGRemotes.fetchSlaveConfig($slaveId) - client not defined")
    }
  }

  private def initRemoteMasterClients(): Unit = {
    initClients(configSvc.config.remoteMasters, remoteMasterClients, "masters")
  }

  override def notifyMasters(): Unit = {
    initRemoteMasterClients()
    remoteMasterClients.values.foreach(_.notifyReloadConf())
  }

  /**
    * @inheritdoc
    */
  override def configs: Seq[SMGRemoteConfig] = configSvc.config.remotes.map { rmt =>
    cachedConfigs.get(rmt.id).flatten
  }.filter(_.isDefined).map(_.get)

  /**
    * @inheritdoc
    */
  override def byId(remoteId: String): Option[SMGRemoteConfig] = cachedConfigs.get(remoteId).flatten

  private def clientForId(remoteId: String) = remoteClients.get(remoteId)

  /**
    * @inheritdoc
    */
  override def graphObjects(lst: Seq[SMGObjectView], periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    val byRemote = lst.groupBy(o => SMGRemote.remoteId(o.id))
    val listOfFutures = (for (remoteId <- byRemote.keys ; if clientForId(remoteId).nonEmpty) yield {
        clientForId(remoteId).get.graphObjects(byRemote(remoteId), periods, gopts)
    }).toList
    Future.sequence(listOfFutures).map { sofs =>
      sofs.flatten
    }
  }

  /**
    * @inheritdoc
    */
  override def graphAgg(remoteId:String, aobj: SMGAggObjectView, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.graphAgg(aobj, periods, gopts)
    else Future { Seq() }
  }

  /**
    * @inheritdoc
    */
  override def fetchRows(oid: String, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]] = {
    val remoteId = SMGRemote.remoteId(oid)
    if (clientForId(remoteId).nonEmpty){
      clientForId(remoteId).get.fetchRows(SMGRemote.localId(oid), params)
    } else Future { Seq() }
  }

  /**
    * @inheritdoc
    */
  override def fetchAggRows(aobj: SMGAggObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]] = {
    val remoteId = SMGRemote.remoteId(aobj.id)
    if (clientForId(remoteId).nonEmpty){
      val ids = aobj.objs.map( ov => SMGRemote.localId(ov.id)).toList
      clientForId(remoteId).get.fetchAggRows(ids, aobj.op, params)
    } else Future { Seq() }
  }

  val currentFutures = new java.util.concurrent.ConcurrentHashMap[String,File]()

  override def downloadRrd(robj:SMGObjectView): Future[Option[SMGObjectView]] = {
    if (SMGRemote.isLocalObj(robj.id)) {
      log.warn("SMGRemotes.downloadRrd: Called for local object: " + robj)
      return Future { Some(robj) }
    }
    val remoteId = SMGRemote.remoteId(robj.id)
    val lid = SMGRemote.localId(robj.id)
    val baseDir = rrdCacheBaseDir + "/" + remoteId
    new File(baseDir).mkdirs()
    // cache up to next update schedule divided by 2 or 1 min, whichever smaller
    val secsToNextUpdate = robj.interval - (System.currentTimeMillis() / 1000) % robj.interval
    val cacheFor: Long = Seq[Long]((secsToNextUpdate / 2) * 1000, 60000).min
    val localFn = baseDir + "/" + lid + ".rrd"
    val newFileObj = new File(localFn)
    var fileObj = currentFutures.putIfAbsent(localFn, newFileObj)
    if (fileObj == null) {
      fileObj = newFileObj
    }
    val futSuccess = fileObj.synchronized {
      if (!fileObj.exists || (System.currentTimeMillis() - fileObj.lastModified() > cacheFor)) {
        log.debug("SMGRemotes.downloadRrd: Downloading rrd for " + robj)
        if (clientForId(remoteId).nonEmpty)
          clientForId(remoteId).get.downloadRrd(lid, localFn)
        else
          Future { false }
      } else {
        log.debug("SMGRemotes.downloadRrd: Using cached rrd for " + robj)
        Future { true }
      }
    }

    futSuccess.map { success =>
      currentFutures.remove(localFn)
      if (success)
        Some(new SMGRemoteObjectCopy(robj, localFn))
      else
        None
    }
  }

  override def pluginData(remoteId: String, pluginId: String, httpParams: Map[String, String]): Future[String] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.pluginData(pluginId, httpParams)
    else Future { "" }
  }

  override def monitorLogs(remoteId: String, periodStr: String, limit: Int,
                           minSeverity: Option[SMGState.Value], inclSoft: Boolean,
                           inclAcked: Boolean, inclSilenced: Boolean): Future[Seq[SMGMonitorLogMsg]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorLogs(periodStr, limit, minSeverity, inclSoft, inclAcked, inclSilenced)
    else Future { Seq() }
  }

  def heatmap(remoteId: String,
              flt: SMGFilter,
              ix: Option[SMGIndex],
              maxSize: Option[Int],
              offset: Option[Int],
              limit: Option[Int]): Future[SMGMonHeatmap] = {

    clientForId(remoteId).get.heatmap(flt, ix, maxSize, offset, limit)
  }

  def objectViewsStates(remoteId: String, ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonState]]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.objectViewsStates(ovs)
    else Future { Map() }
  }

  /**
    * TODO
    *
    * @param remoteId
    * @param root
    * @return
    */
  override def monitorRunTree(remoteId: String, root: Option[String]): Future[Map[Int, Seq[SMGFetchCommandTree]]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorRunTree(root)
    else Future { Map() }
  }

  /**
    * remote call to get current (problem) states
    *
    * @param remote
    * @param flt
    * @return
    */
  override def monitorStates(remote: SMGRemote, flt: SMGMonFilter): Future[SMGMonitorStatesResponse] = {
    if (clientForId(remote.id).nonEmpty)
      clientForId(remote.id).get.monitorStates(flt)
    else Future { SMGMonitorStatesResponse(remote, Seq(), isMuted = false) }

  }


  override def monitorSilencedStates(remoteId: String): Future[Seq[SMGMonState]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorSilencedStates()
    else Future { Seq() }
  }

  override def monitorTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String],
                            pg: Int, pgSz: Int): Future[(Seq[SMGTree[SMGMonState]], Int)]   = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorTrees(flt, rootId, pg, pgSz)
    else Future { (Seq(), 0) }

  }

  override def monitorSilenceAllTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String], until: Int): Future[Boolean] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorSilenceAllTrees(flt, rootId, until)
    else Future { false }
  }


  override def monitorAck(id: String): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(id)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorAck(id)
    else Future { false }
  }

  override def monitorUnack(id: String): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(id)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorUnack(id)
    else Future { false }
  }

  override def monitorSilence(id: String, slunt: Int): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(id)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorSilence(id, slunt)
    else Future { false }
  }

  override def monitorUnsilence(id: String): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(id)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorUnsilence(id)
    else Future { false }
  }

  override def monitorMute(remoteId: String): Future[Boolean] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorMute()
    else Future { false }
  }

  override def monitorUnmute(remoteId: String): Future[Boolean] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorUnmute()
    else Future { false }
  }

  /**
    * Acknowledge an error for given monitor states. Acknowledgement is automatically cleared on recovery.
    *
    * @param ids
    * @return
    */
  override def acknowledgeList(remoteId: String, ids: Seq[String]): Future[Boolean] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.acknowledgeList(ids)
    else Future { false }
  }

  /**
    * Silence given states for given time period
    *
    * @param ids
    * @param slunt
    * @return
    */
  override def silenceList(remoteId: String, ids: Seq[String], slunt: Int): Future[Boolean] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.silenceList(ids, slunt)
    else Future { false }
  }
}
