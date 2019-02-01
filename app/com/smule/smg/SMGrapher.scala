package com.smule.smg


import java.io.File

import javax.inject.{Inject, Singleton}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.smule.smg.config.{SMGAutoIndex, SMGConfigService}
import com.smule.smg.core._
import com.smule.smg.grapher._
import com.smule.smg.remote.{SMGRemote, SMGRemotesApi}
import com.smule.smg.rrd.{SMGRrdFetch, SMGRrdFetchAgg, SMGRrdFetchParams, SMGRrdRow}
import com.smule.smg.search.SMGSearchCache

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import monitor._


/**
  * The SMG @GrapherApi imlementation
  *
  * @param configSvc - (injected) SMGConfigService implementation to read configs from
  * @param actorSystem - (injected) Akka actor system to use to send the update/graph messages
  * @param remotes - (injected) - remotes API interface, to be able to talk to remote instances
  */
@Singleton
class SMGrapher @Inject() (configSvc: SMGConfigService,
                           actorSystem: ActorSystem,
                           val remotes: SMGRemotesApi,
                           val searchCache: SMGSearchCache
                          ) extends GrapherApi {

  // TODO need to rethink dependencies (who creates the plugins) to get rid of this
  configSvc.plugins.foreach(_.setGrapherApi(this))

  private val log = SMGLogger

  private val graphActor = actorSystem.actorOf(SMGraphActor.props)

  private val myCommandExecutionTimes: TrieMap[String, Long] = TrieMap[String, Long]()

  override def commandExecutionTimes: Map[String, Long] = myCommandExecutionTimes.toMap

  private def cleanupCommandExecutionTimes(): Unit = {
    val toCheck = myCommandExecutionTimes.keySet
    val myConf = configSvc.config
    toCheck.foreach { id =>
      if (!(myConf.updateObjectsById.contains(id) || myConf.preFetches.contains(id))){
        myCommandExecutionTimes.remove(id)
      }
    }
  }

  private val updateActor: ActorRef = actorSystem.actorOf(Props(new SMGUpdateActor(configSvc, myCommandExecutionTimes)))

  private val messagingEc = configSvc.executionContexts.defaultCtx

  /**
    * @inheritdoc
    */
  override def run(interval: Int):Unit = {
    val conf = configSvc.config
    val commandTrees = conf.getFetchCommandsTrees(interval)
    val sz = if (commandTrees.isEmpty) 0 else commandTrees.map(_.size).sum
    if (sz == 0) {
      log.info(s"SMGrapher.run(interval=$interval): No commands to execute")
      Future { runPlugins(interval) }(messagingEc)
      return
    }
    val aggObjectUpdates = conf.rrdAggObjectsByInterval.getOrElse(interval, Seq())

    // single stage counter if no aggregate object updates are defined, two stages otherwise
    val stages = if (aggObjectUpdates.isEmpty) {
      Array(
        SMGRunStageDef(sz,
          onRunCompleteFunc(s"SMGrapher.run(interval=$interval): run completed with $sz commands executed"))
      )
    } else {
      val aggsSz = aggObjectUpdates.size
      Array(
        SMGRunStageDef(sz, { () =>
          aggObjectUpdates.foreach { obj =>
            updateActor ! SMGUpdateActor.SMGUpdateObjectMessage(obj, None, updateCounters = true)
          }
          log.info(s"SMGrapher.run(interval=$interval): stage 0 done ($sz objects). " +
            s"Sent messages for $aggsSz aggregate objects")
        }),
        SMGRunStageDef(aggsSz,
          onRunCompleteFunc(s"SMGrapher.run(interval=$interval): run completed: $sz commands, $aggsSz agg objects"))
      )
    }

    if (!SMGStagedRunCounter.resetInterval(interval, stages)) {
      log.error(s"SMGrapher.run(interval=$interval): Overlapping runs detected - aborting")
      configSvc.sendRunMsg(SMGDataFeedMsgRun(interval, List("Overlapping runs detected"), None))
      return
    } else {
      configSvc.sendRunMsg(SMGDataFeedMsgRun(interval, List(), None))
    }
    Future {
      commandTrees.foreach { fRoot =>
        updateActor ! SMGUpdateActor.SMGUpdateFetchMessage(interval, Seq(fRoot), None, 1, updateCounters = true)
        log.debug(s"SMGrapher.run(interval=$interval): Sent fetch update message for: ${fRoot.node.id}")
      }
      log.info(s"SMGrapher.run(interval=$interval): sent messages for $sz fetch commands")
      runPlugins(interval)
    }(messagingEc)
  }

  private def onRunCompleteFunc(logMsg: String): () => Unit = {
    def ret(): Unit = {
      cleanupCommandExecutionTimes() // TODO run this less often?
      log.info(logMsg)
    }
    ret
  }

  private def runPlugins(interval: Int): Unit = {
    configSvc.plugins.foreach { p =>
      if (p.interval == interval) p.run()
    }
  }

  override def runCommandsTree(interval: Int, cmdId: String): Boolean = {
    val conf = configSvc.config
    val commandTrees = conf.getFetchCommandsTrees(interval)
    val topLevel = commandTrees.find(t => t.findTree(cmdId).isDefined)
    if (topLevel.isDefined){
      val root = topLevel.get.findTree(cmdId).get
      updateActor ! SMGUpdateActor.SMGUpdateFetchMessage(interval, Seq(root), None, root.node.childConc, updateCounters = false)
      log.info(s"SMGrapher.runCommandsTree(interval=$interval): Sent fetch update message for: " + root.node)
      true
    } else {
      log.warn(s"SMGrapher.runCommandsTree(interval=$interval): could not find commands tree with root id $cmdId")
      false
    }
  }

  private def filterTopLevel(indexes: Seq[SMGIndex]) = indexes.filter( ix => ix.parentId.isEmpty)

  /**
    * @inheritdoc
    */
  override def getTopLevelIndexesByRemote(rmtIds: Seq[String]): Seq[(SMGRemote, Seq[SMGIndex])] = {
    if (rmtIds.isEmpty || rmtIds.contains(SMGRemote.wildcard.id)) {
      Seq(Tuple2(SMGRemote.local, filterTopLevel(configSvc.config.indexes))) ++
        (for(c <- remotes.configs) yield (c.remote, filterTopLevel(c.indexes)))
    } else {
      rmtIds.flatMap { rid =>
        if (rid == SMGRemote.local.id){
          Seq(Tuple2(SMGRemote.local, filterTopLevel(configSvc.config.indexes)))
        } else {
          val myRmt = remotes.configs.find(c => c.remote.id == rid)
          if (myRmt.isEmpty) {
            Seq()
          } else {
            Seq(Tuple2(myRmt.get.remote, filterTopLevel(myRmt.get.indexes)))
          }
        }
      }
    }
  }

  /**
    * @inheritdoc
    */
  override def getIndexById(id: String): Option[SMGIndex] = {
   if (SMGRemote.isRemoteObj(id)) {
      remotes.byId(SMGRemote.remoteId(id)) match {
        case Some(rc) =>  rc.indexesById.get(id)
        case None => None
      }
    } else {
     configSvc.config.indexesById.get(id)
    }
  }


  private def pluginObjectViews: Seq[SMGObjectView] = {
    configSvc.plugins.flatMap(p => p.objects)
  }

  private def getLocalObject(id: String) : Option[SMGObjectView] = {
    configSvc.config.viewObjectsById.get(id)
  }

  private def getRemoteObject(id: String) : Option[SMGObjectView] = {
    remotes.byId(SMGRemote.remoteId(id)) match {
      case Some(rc) =>  rc.viewObjectsById.get(id)
      case None => None
    }
  }

  /**
    * @inheritdoc
    */
  override def getObjectView(id: String): Option[SMGObjectView] =  {
    if (SMGRemote.isRemoteObj(id)) getRemoteObject(id) else getLocalObject(id)
  }

  /**
    * @inheritdoc
    */
  override def getObjectDetailGraphs(obj:SMGObjectView, gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    graphObject(obj, detailPeriods, gopts)
  }

  /**
    * @inheritdoc
    */
  override def getFilteredObjects(filter: SMGFilter, ix: Option[SMGIndex]): Seq[SMGObjectView]  = {
    val toFilter = if (filter.remotes.isEmpty) {
      configSvc.config.viewObjects
    } else if (filter.remotes.contains(SMGRemote.wildcard.id)) {
      configSvc.config.viewObjects ++ remotes.configs.flatMap(cfg => cfg.viewObjects)
    } else {
      filter.remotes.flatMap { rmtId =>
        if (rmtId == SMGRemote.local.id) {
          configSvc.config.viewObjects
        } else {
          val remoteConf = remotes.byId(rmtId)
          if (remoteConf.nonEmpty) remoteConf.get.viewObjects else Seq()
        }
      }
    }
    toFilter.filter { obj =>
      if (ix.isDefined) {
        ix.get.flt.matches(obj) && filter.matches(obj)
      } else
        filter.matches(obj)
    }
  }

  private def getBasePngFn(oid: String, period: String, gopts: GraphOptions) = {
    oid + gopts.fnSuffix(period) + ".png"
  }

  private def graphLocalObject(obj:SMGObjectView, period: String, gopts:GraphOptions): Future[SMGImageView] = {
    val baseFn = getBasePngFn(obj.id, period, gopts)
    val config = configSvc.config
    implicit val timeout: Timeout = 120000
    if (obj.isAgg) {
      graphLocalAggObject(obj.asInstanceOf[SMGAggObjectViewLocal], period, gopts)
    } else {
      val msg = SMGraphActor.SMGraphMessage(configSvc, obj, period, gopts, new File(config.imgDir, baseFn).toString)
      (graphActor ? msg).mapTo[SMGraphActor.SMGraphReadyMessage].map { resp: SMGraphActor.SMGraphReadyMessage =>
        log.debug("SMGrapher.graphObject: received response: " + resp)
        if (resp.error)
          SMGImage.errorImage(obj, period, gopts, None)
        else
          SMGImage(obj, period, config.urlPrefix + "/" + baseFn, gopts)
      }(messagingEc) // TODO or use graphCtx?
    }
  }

  /**
    * @inheritdoc
    */
  override def graphObject(obj:SMGObjectView, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    if (SMGRemote.isRemoteObj(obj.id)) {
      remotes.graphObjects(Seq(obj), periods, gopts)
    } else {
      graphLocalObjects(Seq(obj), periods, gopts)
    }
  }

  private def graphLocalObjects(lst: Seq[SMGObjectView], periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    val localFutures = lst.flatMap {o =>
      periods.map{ period => graphLocalObject(o, period, gopts) }
    }
    Future.sequence(localFutures)
  }

  /**
    * @inheritdoc
    */
  override def graphObjects(lst: Seq[SMGObjectView], periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    val locRemote = lst.partition(o => SMGRemote.isLocalObj(o.id))
    val localFuture = graphLocalObjects(locRemote._1, periods, gopts)
    val remoteFuture = remotes.graphObjects(locRemote._2, periods, gopts)
    Future.sequence(Seq(localFuture, remoteFuture)).map { sofs =>
      val byId = sofs.flatten.groupBy(_.obj.id).map(t => (t._1, t._2))
      lst.flatMap { ov =>
        val opt = byId.get(ov.id)
        if (opt.isEmpty) { // should never happen ...
          log.error(s"Unexpected error in graphObjects opt.isEmpty: $ov : $byId")
        }
        opt.getOrElse(Seq())
      }
    }
  }


  // "best effort" caching for auto indexes
  private val autoIndexSyncObj = new Object()
  private var prevLocalObjects = configSvc.config.viewObjects
  private var prevRemoteConfs = remotes.configs
  private var cachedAutoIndex: Option[SMGAutoIndex] = None

  def createAutoIndex: SMGAutoIndex = {
    log.info("SMGrapher.createAutoIndex: Refreshing automatic index ...")
    val topLevelId = ""
    val toplLevelLocal = SMGAutoIndex.getAutoIndex(prevLocalObjects.map(o => o.id), "", None)
    val topLevelRemotes = prevRemoteConfs.flatMap( cf =>
      SMGAutoIndex.getAutoIndex(cf.viewObjects.map(o => o.id), "", Some(cf.remote.id))
    )
    SMGAutoIndex(topLevelId, toplLevelLocal ++ topLevelRemotes, None, None)
  }

  /**
    * @inheritdoc
    */
  override def getAutoIndex: SMGAutoIndex = {
    autoIndexSyncObj.synchronized {
      if (cachedAutoIndex.isEmpty || (prevLocalObjects != configSvc.config.viewObjects) || (prevRemoteConfs != remotes.configs)) {
        prevLocalObjects = configSvc.config.viewObjects
        prevRemoteConfs = remotes.configs
        cachedAutoIndex = Some(createAutoIndex)
      }
      cachedAutoIndex.get
    }
  }

  private def graphLocalAggObject(obj:SMGAggObjectView, period: String, gopts: GraphOptions): Future[SMGAggImage] = {
    val baseFn = getBasePngFn(obj.id, period, gopts)
    val config = configSvc.config
    implicit val timeout: Timeout = 120000
    val msg = SMGraphActor.SMGraphAggMessage(configSvc, obj,period, gopts, new File(config.imgDir, baseFn).toString)
    (graphActor ? msg).mapTo[SMGraphActor.SMGraphReadyMessage].map { resp:SMGraphActor.SMGraphReadyMessage =>
      log.debug("SMGrapher.graphAggObject: received response: " + resp )
      SMGAggImage(obj, period, if (resp.error) "/assets/images/error.png" else config.urlPrefix + "/" + baseFn, gopts)
    }(messagingEc) // TODO or use graphCtx?
  }

  private def getXRemoteLocalCopies(aobj:SMGAggObjectView): Future[Option[SMGAggObjectViewLocal]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    val futObjs = for (o <- aobj.objs)
      yield if (SMGRemote.isRemoteObj(o.id))
        remotes.downloadRrd(o)
      else
        Future { Some(o) }
    Future.sequence(futObjs).map[Option[SMGAggObjectViewLocal]] { objOpts =>
      if (!objOpts.exists(_.isEmpty)) {
        Some(SMGAggObjectViewLocal(aobj.id, objOpts.map(_.get),
          aobj.op,
          aobj.groupBy,
          aobj.vars,
          aobj.cdefVars,
          aobj.graphVarsIndexes,
          "(Cross-remote) " + aobj.title
        )
        )
      } else None
    }
  }

  private def graphAggObjectXRemote(aobj:SMGAggObjectView, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    getXRemoteLocalCopies(aobj).flatMap{ myaobj =>
      Future.sequence(for (p <- periods) yield {
        if (myaobj.isEmpty)
          Future { SMGImage.errorImage(aobj, p, gopts, None) }
        else
          graphLocalAggObject(myaobj.get, p, gopts)
      })
    }
  }

  /**
    * @inheritdoc
    */
  override def graphAggObject(aobj:SMGAggObjectView, periods: Seq[String], gopts: GraphOptions, xRemote: Boolean): Future[Seq[SMGImageView]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    if (xRemote) {
      graphAggObjectXRemote(aobj, periods, gopts)
    } else {
      val byRemote = aobj.splitByRemoteId
      val localFuts = Future.sequence(if (byRemote.contains(SMGRemote.local.id)) {
        for (p <- periods) yield graphLocalAggObject(byRemote(SMGRemote.local.id), p, gopts)
      } else Seq())
      val remoteFuts = for (rc <- remotes.configs; if byRemote.contains(rc.remote.id))
        yield {
          remotes.graphAgg(rc.remote.id, byRemote(rc.remote.id), periods, gopts)
        }
      Future.sequence(Seq(localFuts) ++ remoteFuts).map { sofs =>
        sofs.flatten
      }
    }
  }

  /**
    *
    * @param obj
    * @param params
    * @return
    */
  override def fetch(obj: SMGObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    if (obj.isAgg) return fetchAgg(obj.asInstanceOf[SMGAggObjectViewLocal], params)
    if (SMGRemote.isRemoteObj(obj.id)) {
      remotes.fetchRows(obj.id, params)
    } else {
      Future {
        new SMGRrdFetch(configSvc.config.rrdConf, obj).fetch(params)
      }
    }
  }

  private def fetchManyLocal(objs: Seq[SMGObjectView],
                             params: SMGRrdFetchParams): Future[Seq[(String, Seq[SMGRrdRow])]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    val localFuts = objs.map { obj =>
        fetch(obj, params).map(rows => (obj.id, rows))
      }
    Future.sequence(localFuts)
  }

  override def fetchMany(objs: Seq[SMGObjectView],
                         params: SMGRrdFetchParams): Future[Seq[(String, Seq[SMGRrdRow])]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    val locRemote = objs.partition(o => SMGRemote.isLocalObj(o.id))
    val localFut = fetchManyLocal(locRemote._1, params)
    val remoteFut = remotes.fetchRowsMany(locRemote._2.map(_.id), params)
    Future.sequence(Seq(localFut, remoteFut)).map { sofs =>
      val map = sofs.flatten.toMap
      objs.map { o =>
        (o.id, map.getOrElse(o.id, Seq()))
      }
    }
  }


  override def fetchAgg(obj: SMGAggObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]] = {
    implicit val myEc = configSvc.executionContexts.rrdGraphCtx
    if (SMGRemote.isRemoteObj(obj.id) && (!obj.objs.exists(o => SMGRemote.remoteId(obj.id) != SMGRemote.remoteId(o.id)))) {
      remotes.fetchAggRows(obj, params)
    } else {
      getXRemoteLocalCopies(obj).map { myaobj =>
        if (myaobj.isDefined) {
          new SMGRrdFetchAgg(configSvc.config.rrdConf, myaobj.get).fetch(params)
        } else {
          Seq()
        }
      }
    }
  }

  /**
    * Get all indexes which would match this object view
    *
    * @param ov
    * @return
    */
  override def objectIndexes(ov: SMGObjectView): Seq[SMGIndex] = {
    objectsIndexes(Seq(ov))
  }

  override def objectsIndexes(ovs: Seq[SMGObjectView]): Seq[SMGIndex] = {
    searchCache.getMatchingIndexes(ovs)
  }


}
