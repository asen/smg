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
  def graphAgg(remoteId: String, aobj: SMGAggObject, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]]

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
    * @param hardOnly - whether to include soft errors or hard only
    * @return
    */
  def monitorLogs(remoteId: String, periodStr: String, limit: Int,
                  minSeverity: Option[SMGState.Value], hardOnly: Boolean): Future[Seq[SMGMonitorLogMsg]]

  /**
    * get all problematic SMGMonStates from teh given remote
    * @param remoteId - id of the remote to get data from
    * @param includeSoft - whether to include soft errors or hard only
    * @param includeAcked - whether to include acked errors or not
    * @return list of problenatic mon states
    */
  def monitorIssues(remoteId: String, includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Future[Seq[SMGMonState]]


  /**
    * silence/unsilence a problem
    * @param oid
    * @param act
    * @return
    */
  def monitorSilence(oid: String, act: SMGMonSilenceAction): Future[Boolean]


  def monitorSilenceFetchCommand(cmdId: String, until: Option[Int]): Future[Boolean]

  /**
    * Request heatmap from the given remote. A heatmap is (possibly condensed) list of SMGMonState squares.
    * @param remoteId - id of the remote to get data from
    * @param flt - filter to use to get objects
    * @param maxSize - limit the heatmap to that many squares (note max width is enforced separately).
    * @param offset - offset in the filtered objects list to start the heatmap from
    * @param limit - limit the number of filtered objects to include
    * @return
    */
  def heatmap(remoteId: String, flt: SMGFilter, maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[SMGMonHeatmap]


  /**
    * TODO
    * @param remoteId
    * @param root
    * @return
    */
  def monitorRunTree(remoteId: String, root: Option[String]): Future[Map[Int,Seq[SMGFetchCommandTree]]]


  def monProblems(remoteId: String, flt: SMGMonFilter): Future[Seq[SMGMonState]]

  def monSilencedStates(remoteId: String): Future[Seq[SMGMonState]]

  def monTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String], pg: Int, pgSz: Int): Future[(Seq[SMGTree[SMGMonState]], Int)]

  def monAck(id: String): Future[Boolean]

  def monUnack(id: String): Future[Boolean]

  def monSilence(id: String, slunt: Int): Future[Boolean]

  def monUnsilence(id: String): Future[Boolean]

  /**
    * TODO
    * @param cmdId
    * @return
    */
  def monitorFetchCommandState(cmdId: String): Future[Option[SMGMonState]]
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

  override def notifySlaves() = {
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
  override def fetchConfigs() = {
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
        callSystemGc("fetchConfigs")
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
        callSystemGc(s"fetchConfigs($slaveId)")
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

  private def callSystemGc(ctx: String): Unit = {
    // XXX looks like java is leaking direct memory buffers (or possibly - just slowing
    // down external commands when reclaiming these on the fly) when reloading conf.
    // This is an attempt to fix that after realizing that a "manual gc" via jconsole clears overlap issues
    // TODO make this configurable?
    log.info(s"SMGRemotes ($ctx) calling System.gc() ... START")
    System.gc()
    log.info(s"SMGRemotes ($ctx) calling System.gc() ... DONE")
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
  override def graphAgg(remoteId:String, aobj: SMGAggObject, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
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
                           minSeverity: Option[SMGState.Value], hardOnly: Boolean): Future[Seq[SMGMonitorLogMsg]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorLogs(periodStr, limit, minSeverity, hardOnly)
    else Future { Seq() }
  }

  override def monitorIssues(remoteId: String, includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Future[Seq[SMGMonState]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorIssues(includeSoft, includeAcked, includeSilenced)
    else Future { Seq() }
  }

  def monitorSilence(oid: String, act: SMGMonSilenceAction): Future[Boolean]  = {
    val remoteId: String = SMGRemote.remoteId(oid)
    val localId = SMGRemote.localId(oid)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorSilence(localId, act)
    else Future { false }
  }

  def monitorSilenceFetchCommand(cmdId: String, until: Option[Int]): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(cmdId)
    val localId = SMGRemote.localId(cmdId)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorSilenceFetchCommand(localId, until)
    else Future { false }
  }

  def heatmap(remoteId: String,
              flt: SMGFilter,
              maxSize: Option[Int],
              offset: Option[Int],
              limit: Option[Int]): Future[SMGMonHeatmap] = {

    clientForId(remoteId).get.heatmap(flt, maxSize, offset, limit)
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

  override def monitorFetchCommandState(cmdId: String): Future[Option[SMGMonState]] = {
    val remoteId = SMGRemote.remoteId(cmdId)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorFetchCommandState(cmdId)
    else Future { None }
  }

  override def monProblems(remoteId: String, flt: SMGMonFilter): Future[Seq[SMGMonState]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorProblems(flt)
    else Future { Seq() }

  }

  override def monSilencedStates(remoteId: String): Future[Seq[SMGMonState]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorSilencedStates()
    else Future { Seq() }
  }

  override def monTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String],
                        pg: Int, pgSz: Int): Future[(Seq[SMGTree[SMGMonState]], Int)]   = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorTrees(flt, rootId, pg, pgSz)
    else Future { (Seq(), 0) }

  }

  override def monAck(id: String): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(id)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monAck(id)
    else Future { false }
  }

  override def monUnack(id: String): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(id)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monUnack(id)
    else Future { false }
  }

  override def monSilence(id: String, slunt: Int): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(id)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monSilence(id, slunt)
    else Future { false }
  }

  override def monUnsilence(id: String): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(id)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monUnsilence(id)
    else Future { false }
  }

}
