package com.smule.smg.remote

import java.io.File

import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core._
import com.smule.smg.grapher.{GraphOptions, SMGAggObjectView, SMGImageView}
import com.smule.smg.monitor._
import com.smule.smg.rrd.{SMGRrdFetchParams, SMGRrdRow}
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.ws.WSClient

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/**
  * Created by asen on 11/19/15.
  */

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

  override def fetchRowsMany(remoteOids: Seq[String], params: SMGRrdFetchParams): Future[Seq[(String,Seq[SMGRrdRow])]] = {
    val oidsByRemote = remoteOids.groupBy(s => SMGRemote.remoteId(s))
    val futs = oidsByRemote.map { case (rmtId, oids) =>
      if (clientForId(rmtId).nonEmpty){
        val ids = oids.map(s => SMGRemote.localId(s))
        clientForId(rmtId).get.fetchRowsMany(ids, params).map { mm =>
          mm.map(t => (SMGRemote.prefixedId(rmtId, t._1), t._2))
        }
      } else Future { Seq() }
    }
    Future.sequence(futs).map { sofmaps =>
      val map = sofmaps.flatten.toMap
      remoteOids.map { oid =>
        (oid, map.getOrElse(oid, Seq()))
      }
    }
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

  override def monitorLogs(remoteId: String, flt: SMGMonitorLogFilter): Future[Seq[SMGMonitorLogMsg]] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorLogs(flt)
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


  override def monitorSilenced(remoteId: String): Future[(Seq[SMGMonState], Seq[SMGMonStickySilence])] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorSilenced()
    else Future { (Seq(), Seq()) }
  }

  override def monitorTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String],
                            limit: Int): Future[(Seq[SMGTree[SMGMonState]], Int)]   = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorTrees(flt, rootId, limit)
    else Future { (Seq(), 0) }

  }

  override def monitorSilenceAllTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String], until: Int,
                                      sticky: Boolean, stickyDesc: Option[String]): Future[Boolean] = {
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.monitorSilenceAllTrees(flt, rootId, until, sticky, stickyDesc)
    else Future { false }
  }

  override def removeStickySilence(remoteUid: String): Future[Boolean] = {
    val remoteId = SMGRemote.remoteId(remoteUid)
    if (clientForId(remoteId).nonEmpty)
      clientForId(remoteId).get.removeStickySilence(remoteUid)
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
