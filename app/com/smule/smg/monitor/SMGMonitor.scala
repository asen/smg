package com.smule.smg.monitor

import java.io.{File, FileWriter}

import javax.inject.{Inject, Singleton}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import com.smule.smg._
import com.smule.smg.config.{SMGConfigReloadListener, SMGConfigService, SMGLocalConfig}
import com.smule.smg.core._
import com.smule.smg.grapher.SMGAggObjectView
import com.smule.smg.remote.{SMGRemote, SMGRemotesApi}

/**
  * Created by asen on 11/12/16.
  */

@Singleton
class SMGMonitor @Inject()(configSvc: SMGConfigService,
                           smg: GrapherApi,
                           remotes: SMGRemotesApi,
                           val monLogApi: SMGMonitorLogApi,
                           notifSvc: SMGMonNotifyApi,
                           lifecycle: ApplicationLifecycle) extends SMGMonitorApi
  with SMGDataFeedListener with SMGConfigReloadListener {

  configSvc.registerDataFeedListener(this)
  configSvc.registerReloadListener(this)

  val log = SMGLogger

  private val MAX_STATES_PER_CHUNK = 2500

  private def monStateDir = configSvc.config.monStateDir

  private val MONSTATE_META_FILENAME = "metadata.json"
  private val MONSTATE_BASE_FILENAME = "monstates"
  private val NOTIFY_STATES_FILENAME = "notif.json"
  private val STICKY_SILENCES_FILENAME = "sticky.json"

  private def monStateMetaFname = s"$monStateDir/$MONSTATE_META_FILENAME"
  private def monStateBaseFname = s"$monStateDir/$MONSTATE_BASE_FILENAME"
  private def notifyStatesFname = s"$monStateDir/$NOTIFY_STATES_FILENAME"
  private def stickySilencesFname = s"$monStateDir/$STICKY_SILENCES_FILENAME"

  private val myStickySilencesSyncObj = new Object()
  private var myStickySilences: List[SMGMonStickySilence] = List()

  private val allMonitorStatesById = TrieMap[String, SMGMonInternalState]()

  private var topLevelMonitorStateTrees = createStateTrees(configSvc.config)
  private var allMonitorStateTreesById = buildIdToTreeMap(topLevelMonitorStateTrees)

  def findTreeWithRootId(rootId: String): Option[SMGTree[SMGMonInternalState]] = {
    allMonitorStateTreesById.get(rootId)
  }

  private def monStateStickySilencedUntil(ms: SMGMonState): Option[Int] = {
    val matching = localStickySilences.filter(ss => ss.flt.matchesState(ms))
    if (matching.isEmpty){
      None
    } else {
      Some(matching.maxBy(_.silenceUntilTs).silenceUntilTs)
    }
  }

  private def getOrCreateState[T <: SMGMonInternalState](stateId: String,
                                                         createFn: () => SMGMonInternalState,
                                                         updateFn: Option[(T) => Unit]
                                                    ): T = {

    def wrappedCreateFn(): SMGMonInternalState = {
      val ret = createFn()
      val stickySilencedUntil = monStateStickySilencedUntil(ret)
      if (stickySilencedUntil.isDefined)
        ret.slnc(stickySilencedUntil.get)
      ret
    }

    val ret = allMonitorStatesById.getOrElseUpdate(stateId, { wrappedCreateFn() })
    try {
      val myRet = ret.asInstanceOf[T]
      if (updateFn.isDefined) updateFn.get(myRet)
      myRet
    } catch {
      case cc: ClassCastException => {
        // this should never happen
        log.ex(cc, s"Incompatible monitor state returned for var state: $ret")
        val myRet = wrappedCreateFn()
        allMonitorStatesById(myRet.id) = myRet
        myRet.asInstanceOf[T]
      }
    }
  }

  private def getOrCreateVarState(ou: SMGObjectUpdate, vix: Int, update: Boolean = false): SMGMonInternalVarState = {
    val stateId = SMGMonInternalVarState.stateId(ou, vix)
    def createFn() = { new SMGMonInternalVarState(ou, vix, configSvc, monLogApi, notifSvc) }
    def updateFn(state: SMGMonInternalVarState): Unit = {
      if (state.objectUpdate != ou) {
        // this is logged at object level
        //log.warn(s"Updating changed object var state with id ${ret.id}")
        state.objectUpdate = ou
      }
    }
    getOrCreateState[SMGMonInternalVarState](stateId, createFn _, if (update) Some(updateFn) else None)
  }

  private def getOrCreateObjState(ou: SMGObjectUpdate, update: Boolean = false): SMGMonInternalObjState = {
    val stateId = SMGMonInternalObjState.stateId(ou)
    def createFn() = { new SMGMonInternalObjState(ou, configSvc, monLogApi, notifSvc) }
    def updateFn(state: SMGMonInternalObjState) = {
      if (state.objectUpdate != ou) {
        log.warn(s"Updating changed object state with id ${state.id}")
        state.objectUpdate = ou
      }
    }
    getOrCreateState[SMGMonInternalObjState](stateId, createFn _, if (update) Some(updateFn) else None)
  }

  private def getOrCreatePfState(pf: SMGPreFetchCmd, intervals: Seq[Int], pluginId: Option[String], update: Boolean = false): SMGMonInternalPfState = {
    val stateId = SMGMonInternalPfState.stateId(pf)
    def createFn() = { new SMGMonInternalPfState(pf, intervals, pluginId, configSvc, monLogApi, notifSvc) }
    def updateFn(state: SMGMonInternalPfState): Unit = {
      if (state.pfCmd != pf) {
        log.warn(s"Updating changed object pre-fetch state with id ${state.id}")
        state.pfCmd = pf
      }
    }
    getOrCreateState[SMGMonInternalPfState](stateId, createFn _, if (update) Some(updateFn) else None)
  }

  private def getOrCreateRunState(interval: Int, pluginId: Option[String]): SMGMonInternalRunState = {
    val stateId = SMGMonInternalRunState.stateId(interval, pluginId)
    def createFn() = { new SMGMonInternalRunState(interval, pluginId, configSvc, monLogApi, notifSvc) }
    getOrCreateState[SMGMonInternalRunState](stateId, createFn _, None)
  }

  private def cleanupAllMonitorStates(newTrees: Seq[SMGTree[SMGMonInternalState]]): Unit = {
    val allStateIds = newTrees.flatMap(_.allNodes.map(_.id)).toSet
    val toDel = allMonitorStatesById.keySet -- allStateIds
    toDel.foreach { delId =>
      val deleted = allMonitorStatesById.remove(delId)
      if (deleted.isDefined && deleted.get.isInstanceOf[SMGMonInternalObjState]) {
        log.warn(s"Removing obsolete object state with id ${deleted.get.id}")
      }
    }
  }

  private def buildIdToTreeMap(topLevel: List[SMGTree[SMGMonInternalState]]): Map[String, SMGTree[SMGMonInternalState]] = {
    val ret = mutable.Map[String, SMGTree[SMGMonInternalState]]()
    def processTree(root: SMGTree[SMGMonInternalState]): Unit = {
      ret(root.node.id) = root
      root.children.foreach(processTree)
    }
    topLevel.foreach(processTree)
    ret.toMap
  }

  private def createStateTrees(config: SMGLocalConfig): List[SMGTree[SMGMonInternalState]] = {
    val seq = config.updateObjects
    val leafsSeq = seq.flatMap { ou =>
      ou.vars.indices.map { vix => getOrCreateVarState(ou,vix, update = true) }
    }
    val objsMap = seq.map { ou =>  getOrCreateObjState(ou, update = true) }.groupBy(_.id).map(t => (t._1,t._2.head) )
    // Combine regular and plugin prefetch states - plugin can references regular state as parent
    val pfsMap = config.preFetches.map { t =>
      (t._1, getOrCreatePfState(t._2, config.preFetchCommandIntervals(t._1), None, update = true))
    } ++ configSvc.plugins.flatMap { pl =>
      pl.preFetches.map { t =>
        (t._1, getOrCreatePfState(t._2, Seq(pl.interval), Some(pl.pluginId), update = true))
      }
    }
    val parentsMap: Map[String,SMGMonInternalState] =  objsMap ++ pfsMap
    SMGTree.buildTrees[SMGMonInternalState](leafsSeq, parentsMap).toList
  }

  // silence all children of silenced nodes which were just created
  private def silenceNewNotSilencedChildren(stree: SMGTree[SMGMonInternalState]) {
    if (stree.node.isSilenced) {
      stree.children.foreach { ctree =>
        // only silence newly created states
        if ((!ctree.node.isSilenced) && ctree.node.justCreated){
          log.info(s"Silencing newly created state with silenced parent: ${ctree.node.id} (parent: ${stree.node.id})")
          ctree.node.slnc(stree.node.silencedUntil.getOrElse(0)) // in case it just expired
        }
      }
    }
    stree.node.justCreated = false
    stree.children.foreach(silenceNewNotSilencedChildren)
  }

  override def reload(): Unit = {
    topLevelMonitorStateTrees = createStateTrees(configSvc.config)
    cleanupAllMonitorStates(topLevelMonitorStateTrees)
    topLevelMonitorStateTrees.foreach(silenceNewNotSilencedChildren)
    allMonitorStateTreesById = buildIdToTreeMap(topLevelMonitorStateTrees)
    allMonitorStatesById.values.foreach(_.configReloaded())
    notifSvc.configReloaded()
  }

  override def receiveObjMsg(msg: SMGDataFeedMsgObj): Unit = {
    log.debug(s"SMGMonitor: receive: SMGDataFeedMsgObj: ${msg.obj.id} (${msg.obj.interval}/${msg.obj.pluginId})")
    val objState = getOrCreateObjState(msg.obj)
    if ((msg.exitCode != 0) || msg.errors.nonEmpty) {
      // process object error
      objState.processError(msg.ts, msg.exitCode, msg.errors, isInherited = false)
      //process var states
      msg.obj.vars.zipWithIndex.foreach { case (v,ix) =>
        val varState = getOrCreateVarState(msg.obj, ix)
        varState.addState(objState.currentState, isInherited = true)
      }
    } else {
      //process object OK
      objState.processSuccess(msg.ts, isInherited = false)
      //process var states
      msg.vals.zipWithIndex.foreach { case (v,ix) =>
        val varState = getOrCreateVarState(msg.obj, ix)
        varState.processValue(msg.ts, v)
      }
    }
  }

  override def receivePfMsg(msg: SMGDataFeedMsgPf): Unit = {
    log.debug(s"SMGMonitor: receive: SMGDataFeedMsgPf: ${msg.pfId} (${msg.interval}/${msg.pluginId})")
    val pf = if (msg.pluginId.isEmpty)
      configSvc.config.preFetches.get(msg.pfId)
    else
      configSvc.pluginsById.get(msg.pluginId.get).flatMap(p => p.preFetches.get(msg.pfId))

    if (pf.isEmpty) {
      log.error(s"SMGMonitor.receivePfMsg: did not find prefetch for id: ${msg.pfId}")
      return
    }
    val pfState = getOrCreatePfState(pf.get, Seq(msg.interval), msg.pluginId) // TODO just lookup insted of create?
    if ((msg.exitCode != 0) || msg.errors.nonEmpty) {
      // process pre-fetch error
      // failing plugin pre-fetch with failing parent non-plugin prefetch is considered inherited
      val myIsInherited = if (msg.pluginId.nonEmpty && pf.get.preFetch.isDefined){
        val parentPfState = allMonitorStatesById.get(pf.get.preFetch.get)
        parentPfState.isDefined &&
          parentPfState.get.pluginId.isEmpty &&
          (parentPfState.get.currentState.state == SMGState.UNKNOWN)
      } else false
      pfState.processError(msg.ts, msg.exitCode, msg.errors, isInherited = myIsInherited)
      //update all children as they are not getting messages
      findTreeWithRootId(pfState.id).foreach { stTree =>
        //filter only same-plugin prefetches as the plugin ones will get their own PfMsg.
        stTree.allNodes.tail.filter {ms => ms.pluginId == msg.pluginId}.foreach {st =>
          st.addState(pfState.currentState, isInherited = true)
        }
      }
    } else {
      //process pre-fetch OK, child fetches will get their own OK msg
      pfState.processSuccess(msg.ts, isInherited = false)
    }
  }

  override def receiveRunMsg(msg: SMGDataFeedMsgRun): Unit = {
    log.debug(s"SMGMonitor: receive: SMGDataFeedMsgRun: ${msg.interval} isOverlap=${msg.isOverlap}")
    val runState: SMGMonInternalRunState = getOrCreateRunState(msg.interval, msg.pluginId)
    if (msg.isOverlap) {
      runState.processOverlap(msg.ts)
    } else {
      runState.processOk(msg.ts)
    }
  }

  private def expandOv(ov: SMGObjectView): Seq[SMGObjectView] = {
    if (ov.isAgg) ov.asInstanceOf[SMGAggObjectView].objs else Seq(ov)
  }

  private def localNonAgObjectStates(ov: SMGObjectView): Seq[SMGMonInternalState]= {
    if (ov.refObj.isEmpty) {
      log.error(s"SMGMonitor.localNonAgObjectStates: Object view with empty refObj received: $ov")
      return Seq()
    }
    val ou = ov.refObj.get
    val gvIxes = if (ov.graphVarsIndexes.nonEmpty) ov.graphVarsIndexes else ou.vars.indices
    gvIxes.map { vix =>
      val stid = SMGMonInternalVarState.stateId(ou, vix)
      allMonitorStatesById.get(stid)
    }.collect { case Some(x) => x }
  }

  def localObjectViewsState(ovs: Seq[SMGObjectView]): Map[String,Seq[SMGMonState]] = {
    ovs.map { ov => (ov.id, localNonAgObjectStates(ov)) }.toMap
  }

  override def objectViewStates(ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonState]]] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val expadedObjs = ovs.map( ov => (ov.id, expandOv(ov))).toMap
    val byRemote = expadedObjs.values.flatten.toSeq.groupBy(ov => SMGRemote.remoteId(ov.id))
    val futs = byRemote.map{ case (rmtId, myOvs) =>
      if (rmtId == SMGRemote.local.id) {
        Future {
          localObjectViewsState(myOvs)
        }
      } else {
        remotes.objectViewsStates(rmtId, myOvs)
      }
    }
    Future.sequence(futs).map { maps =>
      val nonAgsMap = if (maps.isEmpty) {
        if (ovs.nonEmpty)
          log.error(s"objectViewStates: maps.isEmpty: ovs.size=${ovs.size}, ovs.head.id=${ovs.head.id}")
        Map[String,Seq[SMGMonState]]()
      } else if (maps.tail.isEmpty)
        maps.head
      else {
        var ret = mutable.Map[String, Seq[SMGMonState]]()
        maps.foreach(m => ret ++= m)
        ret.toMap
      }
      expadedObjs.map { case(ovid, seq) =>
        (ovid, seq.filter(ov => nonAgsMap.contains(ov.id)).flatMap(ov => nonAgsMap(ov.id)))
      }
    }
  }

  private def localStatesMatching(fltFn: (SMGMonInternalState) => Boolean): Seq[SMGMonInternalState] = {
    val statesBySeverity = topLevelMonitorStateTrees.sortBy(_.node.id).flatMap { tt =>
      tt.findTreesMatching(fltFn)
    }.map(_.node).groupBy(_.currentStateVal)
    statesBySeverity.keys.toSeq.sortBy(-_.id).flatMap(statesBySeverity(_))
  }

  override def localStates(flt: SMGMonFilter, includeInherited: Boolean): Seq[SMGMonState] = {
    val ret = localStatesMatching(flt.matchesState)
    if (includeInherited)
      ret
    else
      ret.filter(!_.isInherited)
  }

  override def states(remoteIds: Seq[String], flt: SMGMonFilter): Future[Seq[SMGMonitorStatesResponse]] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val futs = ListBuffer[Future[SMGMonitorStatesResponse]]()
    if (remoteIds.isEmpty || remoteIds.contains(SMGRemote.wildcard.id)) {
      futs += Future {
        SMGMonitorStatesResponse(SMGRemote.local, localStates(flt, includeInherited = false),
          isMuted = notifSvc.isMuted, notifSvc.getActiveAlerts)
      }
      configSvc.config.remotes.foreach { rmt =>
        futs += remotes.monitorStates(rmt, flt)
      }
    } else {
      remoteIds.foreach { rmtId =>
        if (rmtId == SMGRemote.local.id) {
          futs += Future {
            SMGMonitorStatesResponse(SMGRemote.local, localStates(flt, includeInherited = false),
              isMuted = notifSvc.isMuted, notifSvc.getActiveAlerts)
          }
        } else {
          val rmtOpt = configSvc.config.remotes.find(_.id == rmtId)
          if (rmtOpt.isDefined)
            futs += remotes.monitorStates(rmtOpt.get, flt)
        }
      }
    }
    Future.sequence(futs.toList)
  }

  private def localStateDetail(sid: String): Option[SMGMonStateDetail] = {
    var ret = List[SMGMonState]()
    var cur = allMonitorStatesById.get(sid)
    if (cur.isDefined) ret = cur.get :: ret
    while (cur.isDefined && cur.get.parentId.isDefined){
      cur = allMonitorStatesById.get(cur.get.parentId.get)
      if (cur.isDefined) ret = cur.get :: ret
      if (cur.size > configSvc.config.MAX_RUNTREE_LEVELS + 1){
        log.error(s"SMGMonitor.localStateDetail($sid): SMGMonStateDetail parents " +
          s"exceeded ${configSvc.config.MAX_RUNTREE_LEVELS} levels: $ret")
        cur = None
      }
    }
    // now go through the list creating parents
    var curObj: Option[SMGMonStateDetail] = None
    ret.foreach { ms =>
      val fc = if (ms.oid.isDefined) {
        val ou = configSvc.config.updateObjectsById.get(ms.oid.get)
        if (ou.isDefined && ou.get.isInstanceOf[SMGFetchCommand])
          Some(ou.get.asInstanceOf[SMGFetchCommand])
        else
          None
      } else if (ms.pfId.isDefined) {
        configSvc.config.getPreFetchCommandById(ms.pfId.get)
      } else None
      curObj = Some(SMGMonStateDetail(ms, fc, curObj))
    }
    curObj
  }

  override def localStatesDetails(stateIds: Seq[String]): Map[String, SMGMonStateDetail] = {
    val seq = for(sid <- stateIds ; opt = localStateDetail(sid) ; if opt.isDefined )
      yield (sid, opt.get)
    seq.toMap
  }

  override def statesDetails(stateIds: Seq[String]): Future[Map[String, SMGMonStateDetail]] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val byRemote = stateIds.groupBy(SMGRemote.remoteId)
    val futs = byRemote.toSeq.map { case (remoteId, sids) =>
      if (remoteId == SMGRemote.local.id)
        Future { localStatesDetails(stateIds) }
      else
        remotes.statesDetails(remoteId, sids)
    }
    Future.sequence(futs).map { maps =>
      maps.reduce({(m1,m2) => m1 ++ m2})
    }
  }

  override def localSilencedStates(): (Seq[SMGMonState], Seq[SMGMonStickySilence]) = {
    val states = localStatesMatching({ ms =>
      ms.isSilenced
    })
    (states, localStickySilences)
  }

  override def silencedStates(): Future[Seq[(SMGRemote, Seq[SMGMonState], Seq[SMGMonStickySilence])]] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val remoteFuts = configSvc.config.remotes.map { rmt =>
      remotes.monitorSilenced(rmt.id).map(tpl => (rmt, tpl._1, tpl._2))
    }
    val localFut = Future {
      val t = localSilencedStates()
      (SMGRemote.local, t._1, t._2)
    }
    val allFuts = Seq(localFut) ++ remoteFuts
    Future.sequence(allFuts)
  }

  override def localMatchingMonTrees(flt: SMGMonFilter, rootId: Option[String]): Seq[SMGTree[SMGMonInternalState]] = {
    val allTreesToFilter = if (rootId.isDefined) {
      findTreeWithRootId(rootId.get).map { t =>
        Seq(t)
      }.getOrElse(Seq())
    } else topLevelMonitorStateTrees.sortBy(_.node.id)
    allTreesToFilter.flatMap { tt =>
      tt.findTreesMatching(flt.matchesState)
    }
  }

  private def sanitizeRemoteIdsParam(remoteIds: Seq[String]): Seq[String] = {
    if (remoteIds.contains(SMGRemote.wildcard.id)) {
      Seq(SMGRemote.local.id) ++ configSvc.config.remotes.map(_.id)
    } else if (remoteIds.isEmpty) {
      Seq(SMGRemote.local.id)
    } else {
      remoteIds
    }
  }

  /**
    *
    * @param remoteIds
    * @param flt
    * @param rootId
    * @param limit
    * @return a tuple with the resulting page of trees and the total number of pages
    */
  override def monTrees(remoteIds: Seq[String], flt: SMGMonFilter, rootId: Option[String],
                        limit: Int): Future[(Seq[SMGTree[SMGMonState]], Int)] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val myRemoteIds = sanitizeRemoteIdsParam(remoteIds)
    val futs = myRemoteIds.map { remoteId =>
      if (remoteId == SMGRemote.local.id) {
        Future {
          val trees = localMatchingMonTrees(flt, rootId)
          (trees.take(limit).map(_.asInstanceOf[SMGTree[SMGMonState]]), trees.size)
        }
      } else {
        remotes.monitorTrees(remoteId, flt, rootId, limit)
      }
    }
    Future.sequence(futs).map { seq =>
      var total = 0
      val ret = ListBuffer[SMGTree[SMGMonState]]()
      seq.foreach { tpl =>
        total += tpl._2
        val toTake = limit - ret.size
        val treeSeq = tpl._1.take(toTake)
        ret.appendAll(treeSeq)
      }
      (ret.toList, total)
    }
  }

  override def silenceAllTrees(remoteIds: Seq[String], flt: SMGMonFilter, rootId: Option[String], until: Int,
                               sticky: Boolean, stickyDesc: Option[String]): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val myRemoteIds = sanitizeRemoteIdsParam(remoteIds)
    val futs = myRemoteIds.map { rmtId =>
      if (rmtId == SMGRemote.local.id) {
        Future {
          if (sticky) {
            if (rootId.isEmpty && (flt.minState.getOrElse(SMGState.OK) == SMGState.OK) &&
              flt.includeAcked && flt.includeSilenced && flt.includeSoft) {
              addLocalStickySilence(flt, until, stickyDesc)
            } else {
              log.error("Attempt to use sticky silence with incompatible filter which is not allowed")
            }
          }
          localMatchingMonTrees(flt, rootId).foreach { tlt =>
            tlt.allNodes.foreach(_.slnc(until))
          }
          true
        }
      } else {
        remotes.monitorSilenceAllTrees(rmtId, flt, rootId, until, sticky, stickyDesc)
      }
    }
    Future.sequence(futs).map { seq =>
      seq.exists(b => b)
    }
  }

  private def localStickySilences: Seq[SMGMonStickySilence] = {
    myStickySilencesSyncObj.synchronized {
      myStickySilences = myStickySilences.filter(_.silenceUntilTs > SMGState.tssNow)
      myStickySilences
    }
  }

  private def addLocalStickySilence(flt: SMGMonFilter, until: Int, stickyDesc: Option[String]): Unit = {
    myStickySilencesSyncObj.synchronized {
      myStickySilences = SMGMonStickySilence(flt, until, stickyDesc) :: myStickySilences
    }
  }

  override def removeStickySilence(uid: String): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    if (SMGRemote.isLocalObj(uid)) {
      Future {
        myStickySilencesSyncObj.synchronized {
          myStickySilences = myStickySilences.filter(ss => ss.uuid != uid)
        }
        true
      }
    } else {
      remotes.removeStickySilence(uid)
    }
  }

  private def condenseHeatmapStates(allStates: Seq[SMGMonInternalState], maxSize: Int): (List[SMGMonState], Int) = {
    val chunkSize = (allStates.size / maxSize) + (if (allStates.size % maxSize == 0) 0 else 1)
    val lst = allStates.grouped(chunkSize).map { chunk =>
      val stateIds = chunk.flatMap(_.ouids)
      val agStateId = stateIds.mkString(",")
      SMGMonStateAgg(agStateId, chunk, SMGMonStateAgg.objectsUrlFilter(stateIds))
    }
    (lst.toList, chunkSize)
  }

  override def localHeatmap(flt: SMGFilter, ix: Option[SMGIndex], maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): SMGMonHeatmap = {
    // TODO include global/run issues?
    val objList = smg.getFilteredObjects(flt.asLocalFilter, ix)
    val objsSlice = objList.slice(offset.getOrElse(0), offset.getOrElse(0) + limit.getOrElse(objList.size))
    val allStates = objsSlice.flatMap( ov => localNonAgObjectStates(ov))
    val ct = if (maxSize.isDefined && allStates.nonEmpty) condenseHeatmapStates(allStates, maxSize.get) else (allStates.toList, 1)
    SMGMonHeatmap(ct._1, ct._2)
  }

  override def heatmap(flt: SMGFilter, ix: Option[SMGIndex], maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[Seq[(SMGRemote, SMGMonHeatmap)]] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val myRemotes = if (flt.remotes.isEmpty) {
      Seq(SMGRemote.local)
    } else if (flt.remotes.contains(SMGRemote.wildcard.id)) {
      configSvc.config.allRemotes
    } else {
      val fltSet = flt.remotes.toSet
      configSvc.config.allRemotes.filter(r => fltSet.contains(r.id))
    }
    val futs = myRemotes.map { rmt =>
      if (rmt == SMGRemote.local)
        Future {
          (rmt, localHeatmap(flt, ix, maxSize, offset, limit))
        }
      else
        remotes.heatmap(rmt.id, flt, ix, maxSize, offset, limit).map(mh => (rmt, mh))
    }
    Future.sequence(futs)
  }

  def inspectStateTree(stateId: String): Option[String] = {
    allMonitorStateTreesById.get(stateId).map { mst =>
      mst.allNodes.map(n => n.getClass.toString + ": " + n.inspect).mkString("\n")
    }
  }

  override  def inspectObject(oview:SMGObjectView): Option[String] = {
    val expandedOvs = expandOv(oview)
    val strSeq = expandedOvs.map { ov =>
      if (ov.refObj.isEmpty)
        None
      else {
        val ou = ov.refObj.get
        val stateId = SMGMonInternalObjState.stateId(ou)
        inspectStateTree(stateId) match {
          case Some(x) => Some(x)
          case None => {
            //not in tree
            val objState = allMonitorStatesById.get(stateId)
            if (objState.isDefined) {
              val varStates = localNonAgObjectStates(ov)
              val retStr = (Seq("(Not in tree)" + objState.get) ++ varStates).mkString("\n")
              Some(retStr)
            } else None
          }
        }
      }
    }.collect { case Some(x) => x }
    if (strSeq.isEmpty) {
      None
    } else Some(strSeq.mkString("\n"))
  }

  override def inspectPf(pfId: String): Option[String] = {
    val stateId = SMGMonInternalPfState.stateId(pfId)
    allMonitorStatesById.get(pfId).map(_.serialize.toString())
  }


  private def deserializeObjectsState(stateStr: String): Int = {
    var cnt = 0
    val jsm = Json.parse(stateStr).as[Map[String, JsValue]]
    jsm.foreach { t =>
      val stateOpt = allMonitorStatesById.get(t._1)
      if (stateOpt.isDefined) {
        stateOpt.get.deserialize(t._2)
        cnt += 1
      } else {
        log.warn(s"Ignoring non existing state loaded from disk: $t")
      }
    }
    cnt
  }

  def serializeAllMonitorStates: List[String] = {
    allMonitorStatesById.toList.grouped(MAX_STATES_PER_CHUNK).map { chunk =>
      val om = chunk.map{ t =>
        val k = t._1
        val v = t._2
        (k, v.serialize)
      }
      Json.toJson(om.toMap).toString()
    }.toList
  }

  def saveStateToDisk(): Unit = {
    try {
      log.info("SMGMonitor.saveStateToDisk BEGIN")

      val notifyStatesStr = notifSvc.serializeState().toString()
      val fw3 = new FileWriter(notifyStatesFname, false)
      try {
        fw3.write(notifyStatesStr)
      } finally fw3.close()
      implicit val stickySilenceWrites = SMGMonStickySilence.jsWrites
      val stickySilencesStr = Json.toJson(localStickySilences).toString()
      val fw4 = new FileWriter(stickySilencesFname, false)
      try {
        fw4.write(stickySilencesStr)
      } finally fw4.close()

      new File(monStateDir).mkdirs()
      val oldmf = new File(monStateMetaFname)
      if (oldmf.exists()) {
        oldmf.delete()
      }
      val statesLst = serializeAllMonitorStates
      statesLst.zipWithIndex.foreach { t =>
        val stateStr = t._1
        val ix = t._2
        val suffix = if (ix == 0) "" else s".$ix"
        val monStateFname = s"$monStateBaseFname$suffix.json"
        log.info(s"SMGMonitor.saveStateToDisk $monStateFname")
        val fw = new FileWriter(monStateFname, false)
        try {
          fw.write(stateStr)
        } finally fw.close()
      }
      val metaStr = Json.toJson(Map("stateFiles" -> statesLst.size.toString)).toString()
      val fw1 = new FileWriter(monStateMetaFname, false)
      try {
        fw1.write(metaStr)
      } finally fw1.close()

      log.info("SMGMonitor.saveStateToDisk END")
    } catch {
      case t: Throwable => log.ex(t, "Unexpected exception in SMGMonitor.saveStateToDisk")
    }
  }

  def parseStateMetaData(metaStr: String): Map[String,String] = {
    Json.parse(metaStr).as[Map[String,String]]
  }

  private def loadStateFromDisk(): Unit = {
    log.info("SMGMonitor.loadStateFromDisk BEGIN")
    try {
      val metaD: Map[String,String] = if (new File(monStateMetaFname).exists()) {
        val metaStr = configSvc.sourceFromFile(monStateMetaFname)
        parseStateMetaData(metaStr)
      } else Map()
      var cnt = 0
      val numStateFiles = metaD.getOrElse("stateFiles", "1").toInt
      (0 until numStateFiles).foreach { ix =>
        val suffix = if (ix == 0) "" else s".$ix"
        val monStateFname = s"$monStateBaseFname$suffix.json"
        if (new File(monStateFname).exists()) {
          log.info(s"SMGMonitor.loadStateFromDisk $monStateFname")
          val stateStr = configSvc.sourceFromFile(monStateFname)
          cnt += deserializeObjectsState(stateStr)
        }
      }
      if (new File(notifyStatesFname).exists()) {
        val stateStr = configSvc.sourceFromFile(notifyStatesFname)
        notifSvc.deserializeState(stateStr)
      }
      if (new File(stickySilencesFname).exists()) {
        implicit val jsReads = SMGMonStickySilence.jsReads({s: String => s})
        val jsStr = configSvc.sourceFromFile(stickySilencesFname)
        Json.parse(jsStr).as[Seq[SMGMonStickySilence]].foreach { ss =>
          if (ss.silenceUntilTs > SMGState.tssNow)
            myStickySilences = ss :: myStickySilences
        }
      }
      log.info(s"SMGMonitor.loadStateFromDisk END - $cnt states loaded")
    } catch {
      case x:Throwable => log.ex(x, "SMGMonitor.loadStateFromDisk ERROR")
    }
  }

  private def processTree(id: String, procFn: (SMGMonInternalState) => Unit): Boolean = {
    val mstopt = allMonitorStateTreesById.get(id)
    if (mstopt.isDefined) {
      mstopt.get.allNodes.foreach(procFn)
      true
    } else false
  }

  override def acknowledge(id: String): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    if (SMGRemote.isLocalObj(id)) {
      Future {
        val rootmsopt = allMonitorStateTreesById.get(id)
        if (rootmsopt.isDefined){
          notifSvc.sendAcknowledgementMessages(rootmsopt.get.node)
          processTree(id, {ms => ms.ack()})
        } else false
      }
    } else remotes.monitorAck(id)
  }

  override def unacknowledge(id: String): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    if (SMGRemote.isLocalObj(id)) {
      Future {
        processTree(id, {ms => ms.unack()})
      }
    } else remotes.monitorUnack(id)
  }

  override def silence(id: String, slunt: Int): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    if (SMGRemote.isLocalObj(id)) {
      Future {
        processTree(id, {ms => ms.slnc(slunt)})
      }
    } else remotes.monitorSilence(id, slunt)
  }

  override def unsilence(id: String): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    if (SMGRemote.isLocalObj(id)) {
      Future {
        processTree(id, {ms => ms.unslnc()})
      }
    } else remotes.monitorUnsilence(id)
  }


  private def groupByNonInheritedParent(ids: Seq[String]): Seq[SMGMonInternalState] = {
    val mss = ids.map(id => allMonitorStatesById.get(id)).collect { case Some(ms) => ms }
    // try to group states by top-most failing state
    mss.map { ms =>
      var curPar: Option[SMGMonInternalState] = Some(ms)
      while (curPar.isDefined && curPar.get.isInherited && curPar.get.parentId.isDefined) {
        curPar = allMonitorStatesById.get(curPar.get.parentId.get)
      }
      curPar.getOrElse(ms)
    }.distinct
  }

  /**
    * Acknowledge an error for given monitor states. Acknowledgement is automatically cleared on recovery.
    *
    * @param ids
    * @return
    */
  override def acknowledgeListLocal(ids: Seq[String]): Boolean = {
    val parentMss = groupByNonInheritedParent(ids)
    parentMss.foreach { pms =>
      val rootmsopt = allMonitorStateTreesById.get(pms.id)
      if (rootmsopt.isDefined){
        if (!rootmsopt.get.node.isAcked)
          notifSvc.sendAcknowledgementMessages(rootmsopt.get.node)
        processTree(pms.id, {ms => ms.ack()})
      }
    }
    true
  }

  private def groupByCommonParents(ids: Seq[String]): Seq[SMGMonInternalState] = {
    // check if ids are object view ids and convert to object update ids
    var mss = ids.map{id =>
      val voopt = configSvc.config.viewObjectsById.get(id)
      if (voopt.isDefined)
        voopt.get.refObj.map(ou => ou.id).getOrElse(id)
      else
        id
    }.distinct.map { id => allMonitorStatesById.get(id) }.collect { case Some(ms) => ms }
    // group states which represent common parent and replace them with parent
    var searchMore = true
    while (searchMore) {
      searchMore = false
      mss = mss.groupBy(_.parentId).flatMap { t =>
        val pid = t._1
        val seq = t._2
        if (pid.isEmpty)
          seq
        else {
          val tn = allMonitorStateTreesById.get(pid.get)
          if (tn.isEmpty)
            seq
          else {
            val childIds = tn.get.children.map(_.node.id).sorted.mkString(",")
            val sortedIds = seq.map(_.id).sorted.mkString(",")
            if (childIds != sortedIds)
              seq
            else {
              searchMore = true
              Seq(tn.get.node)
            }
          }
        }
      }.toSeq
    }
    mss.distinct
  }

  /**
    * Silence given states for given time period
    *
    * @param ids
    * @param slunt
    * @return
    */
  override def silenceListLocal(ids: Seq[String], slunt: Int): Boolean = {
    val mss = groupByCommonParents(ids)
    mss.foreach { pms =>
      processTree(pms.id, {ms => ms.slnc(slunt)})
    }
    true
  }

  /**
    * Acknowledge an error for given monitor states. Acknowledgement is automatically cleared on recovery.
    *
    * @param ids
    * @return
    */
  override def acknowledgeList(ids: Seq[String]): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val futs = ids.groupBy(id => SMGRemote.remoteId(id)).map { t =>
      val rmtId = t._1
      val rmtIds = t._2
      if (SMGRemote.local.id == rmtId) {
        Future { acknowledgeListLocal(rmtIds) }
      } else remotes.acknowledgeList(rmtId, rmtIds)
    }
    Future.sequence(futs).map(bools => true)
  }

  /**
    * Silence given states for given time period
    *
    * @param ids
    * @param slunt
    * @return
    */
  override def silenceList(ids: Seq[String], slunt: Int): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx
    val futs = ids.groupBy(id => SMGRemote.remoteId(id)).map { t =>
      val rmtId = t._1
      val rmtIds = t._2
      if (SMGRemote.local.id == rmtId) {
        Future { silenceListLocal(rmtIds, slunt) }
      } else remotes.silenceList(rmtId, rmtIds, slunt)
    }
    Future.sequence(futs).map(bools => true)
  }


  private def muteUnmuteCommon(remoteId: String,
                               localMuteUnmute: () => Unit,
                               remoteMuteUnmite: (String) => Future[Boolean]): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.rrdGraphCtx

    def myMuteLocal() =  Future {
      localMuteUnmute()
      true
    }

    if (SMGRemote.wildcard.id == remoteId) {
      val futs = Seq(myMuteLocal()) ++ configSvc.config.remotes.map(rmt => remoteMuteUnmite(rmt.id))
      Future.sequence(futs).map(seq => true)
    } else if (SMGRemote.local.id == remoteId) {
      myMuteLocal()
    } else {
      remoteMuteUnmite(remoteId)
    }

  }

  override def mute(remoteId: String): Future[Boolean] = {
    muteUnmuteCommon(remoteId, notifSvc.muteAll _, remotes.monitorMute)
  }

  override def unmute(remoteId: String): Future[Boolean] = {
    muteUnmuteCommon(remoteId, notifSvc.unmuteAll _, remotes.monitorUnmute)
  }


  lifecycle.addStopHook { () =>
    Future.successful {
      saveStateToDisk()
    }
  }
  loadStateFromDisk()
}
