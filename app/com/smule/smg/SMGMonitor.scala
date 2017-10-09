package com.smule.smg

import java.io.{File, FileWriter}
import javax.inject.{Inject, Singleton}

import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.io.Source

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

  private def monStateDir = configSvc.config.globals.getOrElse("$monstate_dir", "monstate")

  private val MONSTATE_META_FILENAME = "metadata.json"
  private val MONSTATE_BASE_FILENAME = "monstates"
  private val NOTIFY_STATES_FILENAME = "notif.json"

  private def monStateMetaFname = s"$monStateDir/$MONSTATE_META_FILENAME"
  private def monStateBaseFname = s"$monStateDir/$MONSTATE_BASE_FILENAME"
  private def notifyStatesFname = s"$monStateDir/$NOTIFY_STATES_FILENAME"

  private val allMonitorStatesById = TrieMap[String, SMGMonInternalState]()

  private var topLevelMonitorStateTrees = createStateTrees(configSvc.config)
  private var allMonitorStateTreesById = buildIdToTreeMap(topLevelMonitorStateTrees)

  def findTreeWithRootId(rootId: String): Option[SMGTree[SMGMonInternalState]] = {
    allMonitorStateTreesById.get(rootId)
  }

  private def getOrCreateState[T <: SMGMonInternalState](stateId: String,
                                                         createFn: () => SMGMonInternalState,
                                                         updateFn: Option[(T) => Unit]
                                                    ): T = {
    val ret = allMonitorStatesById.getOrElseUpdate(stateId, { createFn() })
    try {
      val myRet = ret.asInstanceOf[T]
      if (updateFn.isDefined) updateFn.get(myRet)
      myRet
    } catch {
      case cc: ClassCastException => {
        // this should never happen
        log.ex(cc, s"Incompatible monitor state returned for var state: $ret")
        val myRet = createFn()
        allMonitorStatesById(myRet.id) = myRet
        myRet.asInstanceOf[T]
      }
    }
  }

  private def getOrCreateVarState(ou: SMGObjectUpdate, vix: Int, update: Boolean = false): SMGMonVarState = {
    val stateId = SMGMonVarState.stateId(ou, vix)
    def createFn() = { new SMGMonVarState(ou, vix, configSvc, monLogApi, notifSvc) }
    def updateFn(state: SMGMonVarState) = {
      if (state.ou != ou) {
        // this is logged at object level
        //log.warn(s"Updating changed object var state with id ${ret.id}")
        state.ou = ou
      }
    }
    getOrCreateState[SMGMonVarState](stateId, createFn, if (update) Some(updateFn) else None)
  }

  private def getOrCreateObjState(ou: SMGObjectUpdate, update: Boolean = false): SMGMonObjState = {
    val stateId = SMGMonObjState.stateId(ou)
    def createFn() = { new SMGMonObjState(ou, configSvc, monLogApi, notifSvc) }
    def updateFn(state: SMGMonObjState) = {
      if (state.ou != ou) {
        log.warn(s"Updating changed object state with id ${state.id}")
        state.ou = ou
      }
    }
    getOrCreateState[SMGMonObjState](stateId, createFn, if (update) Some(updateFn) else None)
  }

  private def getOrCreatePfState(pf: SMGPreFetchCmd, intervals: Seq[Int], pluginId: Option[String], update: Boolean = false): SMGMonPfState = {
    val stateId = SMGMonPfState.stateId(pf)
    def createFn() = { new SMGMonPfState(pf, intervals, pluginId, configSvc, monLogApi, notifSvc) }
    def updateFn(state: SMGMonPfState): Unit = {
      if (state.pfCmd != pf) {
        log.warn(s"Updating changed object pre-fetch state with id ${state.id}")
        state.pfCmd = pf
      }
    }
    getOrCreateState[SMGMonPfState](stateId, createFn, if (update) Some(updateFn) else None)
  }

  private def getOrCreateRunState(interval: Int, pluginId: Option[String]): SMGMonRunState = {
    val stateId = SMGMonRunState.stateId(interval, pluginId)
    def createFn() = { new SMGMonRunState(interval, pluginId, configSvc, monLogApi, notifSvc) }
    getOrCreateState[SMGMonRunState](stateId, createFn, None)
  }

  private def cleanupAllMonitorStates(newTrees: Seq[SMGTree[SMGMonInternalState]]): Unit = {
    val allStateIds = newTrees.flatMap(_.allNodes.map(_.id)).toSet
    val toDel = allMonitorStatesById.keySet -- allStateIds
    toDel.foreach { delId =>
      val deleted = allMonitorStatesById.remove(delId)
      if (deleted.isDefined && deleted.get.isInstanceOf[SMGMonObjState]) {
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
    val ret = config.updateObjects.groupBy(_.pluginId).flatMap { case (plidOpt, seq) =>
      val leafsSeq = seq.flatMap { ou =>
        ou.vars.indices.map { vix => getOrCreateVarState(ou,vix, update = true) }
      }
      val objsMap = seq.map { ou =>  getOrCreateObjState(ou, update = true) }.groupBy(_.id).map(t => (t._1,t._2.head) )
      val pfsMap = if (plidOpt.isEmpty) config.preFetches.map { t =>
        val pfState = getOrCreatePfState(t._2, config.preFetchCommandIntervals(t._1), plidOpt, update = true)
        (pfState.id, pfState)
      } else {
        val pluginOpt = configSvc.pluginsById.get(plidOpt.get)
        pluginOpt.map { pl =>
          pl.preFetches.map { t =>
            val pfState = getOrCreatePfState(t._2, Seq(pl.interval), plidOpt, update = true)
            (pfState.id, pfState)
          }
        }.getOrElse(Map())
      }
      val parentsMap: Map[String,SMGMonInternalState] =  objsMap ++ pfsMap
      SMGTree.buildTrees[SMGMonInternalState](leafsSeq, parentsMap)
    }.toList
    ret
  }

  // silence all children of silenced nodes which were just created
  private def silenceNewNotSilencedChildren(stree: SMGTree[SMGMonInternalState]) {
    if (stree.node.isSilenced) {
      stree.children.foreach { ctree =>
        // only silence newly created states (detected by recentStates.size less than max)
        // XXX TODO this check may fail for states having maxHardErrorCount = 1 together with a race condition
        // (should be a very rare case).
        if ((!ctree.node.isSilenced) && (ctree.node.recentStates.size < ctree.node.maxRecentStates)){
          log.info(s"Silencing newly created state with silenced parent: ${ctree.node.id} (parent: ${stree.node.id})")
          ctree.node.slnc(ctree.node.silencedUntil.getOrElse(0)) // in case it just expired
        }
      }
    }
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

  override def receiveObjMsg(msg: SMGDFObjMsg): Unit = {
    log.debug(s"SMGMonitor: receive: SMGDFObjMsg: ${msg.obj.id} (${msg.obj.interval}/${msg.obj.pluginId})")
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

  override def receivePfMsg(msg: SMGDFPfMsg): Unit = {
    log.debug(s"SMGMonitor: receive: SMGDFPfMsg: ${msg.pfId} (${msg.interval}/${msg.pluginId})")
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
      pfState.processError(msg.ts, msg.exitCode, msg.errors, isInherited = false)
      //update all children as they are not getting messages
      findTreeWithRootId(pfState.id).foreach { stTree =>
        stTree.allNodes.tail.foreach(st => st.addState(pfState.currentState, isInherited = true))
      }
    } else {
      //process pre-fetch OK, child fetches will get their own OK msg
      pfState.processSuccess(msg.ts, isInherited = false)
    }
  }

  override def receiveRunMsg(msg: SMGDFRunMsg): Unit = {
    log.debug(s"SMGMonitor: receive: SMGDFRunMsg: ${msg.interval} isOverlap=${msg.isOverlap}")
    val runState: SMGMonRunState = getOrCreateRunState(msg.interval, msg.pluginId)
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
      val stid = SMGMonVarState.stateId(ou, vix)
      allMonitorStatesById.get(stid)
    }.collect { case Some(x) => x }
  }

  def localObjectViewsState(ovs: Seq[SMGObjectView]): Map[String,Seq[SMGMonState]] = {
    ovs.map { ov => (ov.id, localNonAgObjectStates(ov)) }.toMap
  }

  override def objectViewStates(ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonState]]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
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
    implicit val ec = ExecutionContexts.rrdGraphCtx
    val futs = ListBuffer[(Future[SMGMonitorStatesResponse])]()
    if (remoteIds.isEmpty || remoteIds.contains(SMGRemote.wildcard.id)) {
      futs += Future {
        SMGMonitorStatesResponse(SMGRemote.local, localStates(flt, includeInherited = false), isMuted = notifSvc.isMuted)
      }
      configSvc.config.remotes.foreach { rmt =>
        futs += remotes.monitorStates(rmt, flt)
      }
    } else {
      remoteIds.foreach { rmtId =>
        if (rmtId == SMGRemote.local.id) {
          futs += Future {
            SMGMonitorStatesResponse(SMGRemote.local, localStates(flt, includeInherited = false), isMuted = notifSvc.isMuted)
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


  override def localSilencedStates(): Seq[SMGMonState] = {
    localStatesMatching({ ms =>
      ms.isSilenced
    })
  }

  override def silencedStates(): Future[Seq[(SMGRemote, Seq[SMGMonState])]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    val remoteFuts = configSvc.config.remotes.map { rmt =>
      remotes.monitorSilencedStates(rmt.id).map((rmt,_))
    }
    val localFut = Future {
      (SMGRemote.local, localSilencedStates())
    }
    val allFuts = Seq(localFut) ++ remoteFuts
    Future.sequence(allFuts)
  }

  def localMatchingMonTrees(flt: SMGMonFilter, rootId: Option[String]): Seq[SMGTree[SMGMonInternalState]] = {
    val allTreesToFilter = if (rootId.isDefined) {
      findTreeWithRootId(rootId.get).map { t =>
        Seq(t)
      }.getOrElse(Seq())
    } else topLevelMonitorStateTrees.sortBy(_.node.id)
    allTreesToFilter.flatMap { tt =>
      tt.findTreesMatching(flt.matchesState)
    }
  }

  override def localMonTrees(flt: SMGMonFilter, rootId: Option[String], pg: Int, pgSz: Int): (Seq[SMGTree[SMGMonState]], Int) = {
    val allMatching = localMatchingMonTrees(flt, rootId)
    val ret = SMGTree.sliceTree(allMatching, pg, pgSz)
    val tlToDisplay = allMatching.size + allMatching.map(_.children.size).sum
    val maxPg = (tlToDisplay / pgSz) + (if (tlToDisplay % pgSz == 0) 0 else 1)
    (ret.map(_.asInstanceOf[SMGTree[SMGMonState]]), maxPg)
  }

  /**
    *
    * @param remoteId
    * @param flt
    * @param rootId
    * @param pg
    * @param pgSz
    * @return a tuple with the resulting page of trees and the total number of pages
    */
  override def monTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String],
                        pg: Int, pgSz: Int): Future[(Seq[SMGTree[SMGMonState]], Int)] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    if (remoteId == SMGRemote.local.id) {
      Future {
        localMonTrees(flt, rootId, pg, pgSz)
      }
    } else {
      remotes.monitorTrees(remoteId, flt, rootId, pg, pgSz)
    }
  }

  override def silenceAllTrees(remoteId: String, flt: SMGMonFilter, rootId: Option[String],
                               until: Int): Future[Boolean] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    if (remoteId == SMGRemote.local.id) {
      Future {
        localMatchingMonTrees(flt, rootId).foreach { tlt =>
          tlt.allNodes.foreach(_.slnc(until))
        }
        true
      }
    } else {
      remotes.monitorSilenceAllTrees(remoteId, flt, rootId, until)
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
    val objList = smg.getFilteredObjects(flt, ix).filter(o => SMGRemote.isLocalObj(o.id)) // XXX or clone the filter with empty remote?
    val objsSlice = objList.slice(offset.getOrElse(0), offset.getOrElse(0) + limit.getOrElse(objList.size))
    val allStates = objsSlice.flatMap( ov => localNonAgObjectStates(ov))
    val ct = if (maxSize.isDefined && allStates.nonEmpty) condenseHeatmapStates(allStates, maxSize.get) else (allStates.toList, 1)
    SMGMonHeatmap(ct._1, ct._2)
  }

  override def heatmap(flt: SMGFilter, ix: Option[SMGIndex], maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[Seq[(SMGRemote, SMGMonHeatmap)]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
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
      mst.allNodes.map(_.serialize.toString()).mkString("\n")
    }
  }

  override  def inspectObject(oview:SMGObjectView): Option[String] = {
    val expandedOvs = expandOv(oview)
    val strSeq = expandedOvs.map { ov =>
      if (ov.refObj.isEmpty)
        None
      else {
        val ou = ov.refObj.get
        val stateId = SMGMonObjState.stateId(ou)
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
    val stateId = SMGMonPfState.stateId(pfId)
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
      new File(monStateDir).mkdirs()
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
      val notifyStatesStr = notifSvc.serializeState().toString()
      val fw3 = new FileWriter(notifyStatesFname, false)
      try {
        fw3.write(notifyStatesStr)
      } finally fw3.close()
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
        val metaStr = Source.fromFile(monStateMetaFname).getLines().mkString
        parseStateMetaData(metaStr)
      } else Map()
      var cnt = 0
      val numStateFiles = metaD.getOrElse("stateFiles", "1").toInt
      (0 until numStateFiles).foreach { ix =>
        val suffix = if (ix == 0) "" else s".$ix"
        val monStateFname = s"$monStateBaseFname$suffix.json"
        if (new File(monStateFname).exists()) {
          log.info(s"SMGMonitor.loadStateFromDisk $monStateFname")
          val stateStr = Source.fromFile(monStateFname).getLines().mkString
          cnt += deserializeObjectsState(stateStr)
        }
      }
      if (new File(notifyStatesFname).exists()) {
        val stateStr = Source.fromFile(notifyStatesFname).getLines().mkString
        notifSvc.deserializeState(stateStr)
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
    implicit val ec = ExecutionContexts.rrdGraphCtx
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
    implicit val ec = ExecutionContexts.rrdGraphCtx
    if (SMGRemote.isLocalObj(id)) {
      Future {
        processTree(id, {ms => ms.unack()})
      }
    } else remotes.monitorUnack(id)
  }

  override def silence(id: String, slunt: Int): Future[Boolean] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    if (SMGRemote.isLocalObj(id)) {
      Future {
        processTree(id, {ms => ms.slnc(slunt)})
      }
    } else remotes.monitorSilence(id, slunt)
  }

  override def unsilence(id: String): Future[Boolean] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
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
    implicit val ec = ExecutionContexts.rrdGraphCtx
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
    implicit val ec = ExecutionContexts.rrdGraphCtx
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
    implicit val ec = ExecutionContexts.rrdGraphCtx

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
    muteUnmuteCommon(remoteId, notifSvc.muteAll, remotes.monitorMute)
  }

  override def unmute(remoteId: String): Future[Boolean] = {
    muteUnmuteCommon(remoteId, notifSvc.unmuteAll, remotes.monitorUnmute)
  }


  lifecycle.addStopHook { () =>
    Future.successful {
      saveStateToDisk()
    }
  }
  loadStateFromDisk()

}
