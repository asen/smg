package com.smule.smg


import java.io.File
import javax.inject.{Inject, Singleton}

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


/**
 * Created by asen on 10/22/15.
 */

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
                           val remotes: SMGRemotesApi
                          ) extends GrapherApi {

  // TODO need to rethink dependencies (who creates the plugins) to get rid of this
  configSvc.plugins.foreach(_.setGrapherApi(this))

  private val log = SMGLogger

  private val graphActor = actorSystem.actorOf(SMGraphActor.props)
  private val updateActor = actorSystem.actorOf(Props(new SMGUpdateActor(configSvc)))

  implicit private val messagingEc = ExecutionContexts.defaultCtx

  /**
    * @inheritdoc
    */
  override def run(interval: Int):Unit = {
    val conf = configSvc.config
    val commandTrees = conf.fetchCommandsTree(interval)
    val sz = if (commandTrees.isEmpty) 0 else commandTrees.map(_.size).sum
    if (!SMGRunStats.resetInterval(interval, sz)) {
      log.error("SMGrapher.run(interval=" + interval + "): Overlapping runs detected - aborting")
      configSvc.sendRunMsg(SMGDFRunMsg(interval, List("Overlapping runs detected"), None))
      return
    } else {
      configSvc.sendRunMsg(SMGDFRunMsg(interval, List(), None))
    }
    Future {
      commandTrees.foreach { fRoot =>
        updateActor ! SMGUpdateActor.SMGUpdateFetchMessage(conf.rrdConf, interval, Seq(fRoot), None)
        log.debug("SMGrapher.run(interval=" + interval + "): Sent fetch update message for: " + fRoot.node)
      }
      log.info("SMGrapher.run(interval=" + interval + "): sent messages for " + sz + " fetch commands")
      configSvc.plugins.foreach { p =>
        if (p.interval == interval) p.run()
      }
    }
  }

  private def filterTopLevel(indexes: Seq[SMGIndex]) = indexes.filter( ix => ix.parentId.isEmpty)

  /**
    * @inheritdoc
    */
  override def getTopLevelIndexesByRemote(rmt: Option[String]): Seq[(SMGRemote, Seq[SMGIndex])] = {
    rmt match {
      case Some("") => Seq(Tuple2(SMGRemote.local, filterTopLevel(configSvc.config.indexes)))
      case Some(rmtId) => remotes.byId(rmtId).map { c => Seq(Tuple2(c.remote, filterTopLevel(c.indexes))) }.getOrElse(Seq())
      case None => Seq(Tuple2(SMGRemote.local, filterTopLevel(configSvc.config.indexes))) ++
        (for(c <- remotes.configs) yield (c.remote, filterTopLevel(c.indexes)))
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
  override def getFilteredObjects(filter: SMGFilter): Seq[SMGObjectView]  = {
    val toFilter = if (filter.remote.getOrElse("") == SMGRemote.wildcard.id) {
      configSvc.config.viewObjects ++ remotes.configs.flatMap(cfg => cfg.viewObjects)
    } else {
      val remoteConf = remotes.byId(filter.remote.getOrElse(""))
      if (remoteConf.nonEmpty) remoteConf.get.viewObjects else configSvc.config.viewObjects
    }
    toFilter.filter { obj =>
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
      graphLocalAggObject(obj.asInstanceOf[SMGAggObject], period, gopts)
    } else {
      val msg = SMGraphActor.SMGraphMessage(config.rrdConf, obj, period, gopts, new File(config.imgDir, baseFn).toString)
      (graphActor ? msg).mapTo[SMGraphActor.SMGraphReadyMessage].map { resp: SMGraphActor.SMGraphReadyMessage =>
        log.debug("SMGrapher.graphObject: received response: " + resp)
        if (resp.error)
          SMGImage.errorImage(obj, period, None)
        else
          SMGImage(obj, period, config.urlPrefix + "/" + baseFn)
      }
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
    val localFutures = ( for(o <- lst ; if !SMGRemote.isRemoteObj(o.id))
      yield for (period <- periods) yield graphLocalObject(o, period, gopts) ).flatten
//    implicit val ec = ExecutionContexts.rrdGraphCtx // TODO???
    Future.sequence(localFutures)
  }

  /**
    * @inheritdoc
    */
  override def graphObjects(lst: Seq[SMGObjectView], periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    val localFuture = graphLocalObjects(lst, periods, gopts)
    val remoteObjs = for(o <- lst ; if SMGRemote.isRemoteObj(o.id)) yield o
    val remoteFuture = remotes.graphObjects(remoteObjs, periods, gopts)
//    implicit val ec = ExecutionContexts.rrdGraphCtx
    Future.sequence(Seq(remoteFuture, localFuture)).map { sofs => sofs.flatten }
  }


  // "best effort" caching for auto indexes
  val autoIndexSyncObj = new Object()
  var prevLocalObjects = configSvc.config.viewObjects
  var prevRemoteConfs = remotes.configs
  var cachedAutoIndex: Option[SMGAutoIndex] = None

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

  private def graphLocalAggObject(obj:SMGAggObject, period: String, gopts: GraphOptions): Future[SMGAggImage] = {
    val baseFn = getBasePngFn(obj.id, period, gopts)
    val config = configSvc.config
    implicit val timeout: Timeout = 120000
//    implicit val ec = ExecutionContexts.rrdGraphCtx  // TODO???
    val msg = SMGraphActor.SMGraphMultiMessage(config.rrdConf,obj,period, gopts, new File(config.imgDir, baseFn).toString)
    (graphActor ? msg).mapTo[SMGraphActor.SMGraphReadyMessage].map { resp:SMGraphActor.SMGraphReadyMessage =>
      log.debug("SMGrapher.graphAggObject: received response: " + resp )
      SMGAggImage(obj, period, if (resp.error) "/assets/images/error.png" else config.urlPrefix + "/" + baseFn)
    }
  }

  private def getXRemoteLocalCopies(aobj:SMGAggObjectView): Future[Option[SMGAggObject]] = {
    val futObjs = for (o <- aobj.objs) yield if (SMGRemote.isRemoteObj(o.id)) remotes.downloadRrd(o) else Future { Some(o) }
    Future.sequence(futObjs).map[Option[SMGAggObject]] { objOpts =>
      if (!objOpts.exists(_.isEmpty)) {
        Some(SMGAggObject(aobj.id, objOpts.map(_.get),
          aobj.op,
          aobj.vars,
          aobj.cdefVars,
          aobj.graphVarsIndexes,
          "(X-Remote) " + aobj.title
        )
        )
      } else None
    }
  }

  private def graphAggObjectXRemote(aobj:SMGAggObject, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    getXRemoteLocalCopies(aobj).flatMap{ myaobj =>
      Future.sequence(for (p <- periods) yield {
        if (myaobj.isEmpty)
          Future { SMGImage.errorImage(aobj, p, None) }
        else
          graphLocalAggObject(myaobj.get, p, gopts)
      })
    } (ExecutionContexts.rrdGraphCtx)
  }

  /**
    * @inheritdoc
    */
  override def graphAggObject(aobj:SMGAggObject, periods: Seq[String], gopts: GraphOptions, xRemote: Boolean): Future[Seq[SMGImageView]] = {
    if (xRemote) {
      graphAggObjectXRemote(aobj, periods, gopts)
    } else {
      val byRemote = aobj.splitByRemoteId
      val localFuts = Future.sequence(if (byRemote.contains("")) {
        for (p <- periods) yield graphLocalAggObject(byRemote(""), p, gopts)
      } else Seq())
      val remoteFuts = for (rc <- remotes.configs; if byRemote.contains(rc.remote.id))
        yield remotes.graphAgg(rc.remote.id, byRemote(rc.remote.id), periods, gopts)
      Future.sequence(Seq(localFuts) ++ remoteFuts).map { sofs => sofs.flatten }
    }
  }

  /**
    *
    * @param obj
    * @param params [[SMGRrdFetchParams]]
    * @return
    */
  override def fetch(obj: SMGObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]] = {
    if (obj.isAgg) return fetchAgg(obj.asInstanceOf[SMGAggObject], params)
    if (SMGRemote.isRemoteObj(obj.id)) {
      remotes.fetchRows(obj.id, params)
    } else {
      Future {
        new SMGRrdFetch(configSvc.config.rrdConf, obj).fetch(params)
      }
    }
  }

  override def fetchAgg(obj: SMGAggObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]] = {
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


  private def allIndexes: Seq[SMGIndex] = configSvc.config.indexes ++
    configSvc.config.remotes.flatMap { rmt => // preserving order
      remotes.byId(rmt.id).map(_.indexes).getOrElse(Seq())
    }

  private def allViewObjects: Seq[SMGObjectView] = configSvc.config.viewObjects ++
    configSvc.config.remotes.flatMap { rmt => // preserving order
      remotes.byId(rmt.id).map(_.viewObjects).getOrElse(Seq())
    }

  override def search(q: String, maxResults: Int): Future[Seq[SMGSearchResult]] = {
    Future {
      // search through
      // all indexes (title/desc)
      // objects (title/labels/command)
      val sq = new SMGSearchQuery(q)
      if (sq.isEmpty)
        Seq()
      else {
        val ret = ListBuffer[SMGSearchResult]()
        var cnt = 0
        for (ix <- allIndexes; if cnt < maxResults; if sq.indexMatches(ix)) {
          ret += SMGSearchResultIndex(ix, Seq()) // TODO get matching objects
          cnt += 1
        }
        if (cnt < maxResults) {
          for (ov <- allViewObjects; if cnt < maxResults; if sq.objectMatches(ov)) {
            ret += SMGSearchResultObject(ov)
            cnt += 1
          }
        }
        ret.toList
      }
    }(ExecutionContexts.rrdGraphCtx)
  }


  private def getMatchingIndexes(ovs: Seq[SMGObjectView], allIxes: Seq[SMGIndex]): Seq[SMGIndex] = {
    ovs.flatMap { ov =>
      allIxes.filter { ix =>
        (!ix.flt.matchesAnyObjectIdAndText) &&
          ((ix.flt.remote.getOrElse("") == SMGRemote.wildcard.id) ||
            (SMGRemote.remoteId(ix.id) == SMGRemote.remoteId(ov.id))) &&
          ix.flt.matches(ov)
      }
    }.distinct.sortBy(_.title)
  }

  /**
    * Get all indexes which would match this object view
    *
    * @param ov
    * @return
    */
  override def objectIndexes(ov: SMGObjectView): Seq[SMGIndex] = {
    val nonAgs = if (ov.isAgg) ov.asInstanceOf[SMGAggObjectView].objs else Seq(ov)
    getMatchingIndexes(nonAgs, allIndexes)
  }
}
