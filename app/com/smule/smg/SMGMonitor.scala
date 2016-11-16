package com.smule.smg

import java.io.{File, FileWriter}
import javax.inject.{Inject, Singleton}

import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
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

  private var (topLevelMonitorStateTrees, allMonitorSateTreesById) = createStateTrees(configSvc.config)

  def findTreeWithRootId(rootId: String): Option[SMGTree[SMGMonInternalState]] = {
    allMonitorSateTreesById.get(rootId)
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
        // this is logged at object level
        log.warn(s"Updating changed object state with id ${state.id}")
        state.ou = ou
      }
    }
    getOrCreateState[SMGMonObjState](stateId, createFn, if (update) Some(updateFn) else None)
  }

  private def getOrCreatePfState(pf: SMGPreFetchCmd, interval: Int, update: Boolean = false): SMGMonPfState = {
    val stateId = SMGMonPfState.stateId(pf, interval)
    def createFn() = { new SMGMonPfState(pf, interval, configSvc, monLogApi, notifSvc) }
    def updateFn(state: SMGMonPfState) = {
      if (state.pfCmd != pf) {
        // this is logged at object level
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

  private def createStateTrees(config: SMGLocalConfig): (List[SMGTree[SMGMonInternalState]], Map[String, SMGTree[SMGMonInternalState]]) = {
    val ret = config.updateObjects.groupBy(_.pluginId).flatMap { case (plidOpt, plSeq) =>
      plSeq.groupBy(_.interval).flatMap { case (intvl, seq) =>
        val leafsSeq = seq.flatMap { ou =>
          ou.vars.indices.map { vix => getOrCreateVarState(ou,vix, update = true) }
        }
        val objsMap = seq.map { ou =>  getOrCreateObjState(ou, update = true) }.groupBy(_.id).map(t => (t._1,t._2.head) )
        val pfsMap = config.preFetches.map { t =>
          val pfState = getOrCreatePfState(t._2, intvl, update = true)
          (pfState.id, pfState)
        }
        val runState = getOrCreateRunState(intvl, plidOpt)
        val parentsMap: Map[String,SMGMonInternalState] =  objsMap ++ pfsMap ++ Map(runState.id -> runState)
        SMGTree.buildTree[SMGMonInternalState](leafsSeq, parentsMap)
      }.toList
    }.toList
    cleanupAllMonitorStates(ret)
    (ret, buildIdToTreeMap(ret))
  }

  override def reload(): Unit = {
    val tt = createStateTrees(configSvc.config)
    allMonitorSateTreesById = tt._2
    topLevelMonitorStateTrees = tt._1
    notifSvc.configReloaded()
  }

  override def receiveObjMsg(msg: SMGDFObjMsg): Unit = {
    log.debug("SMGMonitor: receive: " + msg)

    val objState = getOrCreateObjState(msg.obj)

    if ((msg.exitCode != 0) || msg.errors.nonEmpty) {
      // process object error
      objState.processError(msg.ts, msg.exitCode, msg.errors, isInherited = false)
      //process var states
      msg.vals.zipWithIndex.foreach { case (v,ix) =>
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
    log.debug("SMGMonitor: receive: " + msg)
    val pf = configSvc.config.preFetches.get(msg.pfId)
    if (pf.isEmpty) {
      log.error(s"SMGMonitor.receivePfMsg: did not find prefetch for id: ${msg.pfId}")
      return
    }
    val pfState = getOrCreatePfState(pf.get, msg.interval)

    if ((msg.exitCode != 0) || msg.errors.nonEmpty) {
      // process pre-fetch error
      pfState.processError(msg.ts, msg.exitCode, msg.errors, isInherited = false)
      //update children
      findTreeWithRootId(pfState.id).foreach { stTree =>
        stTree.allNodes.tail.foreach(st => st.addState(pfState.currentState, isInherited = true))
      }
    } else {
      //process pre-fetch OK, child pre-fetches will get their own OK msg
      pfState.processSuccess(msg.ts, isInherited = false)
    }
  }

  val runErrorMaxStrikes = 2 // TODO read from config?

  override def receiveRunMsg(msg: SMGDFRunMsg): Unit = {
    log.debug("SMGMonitor: receive: " + msg)
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
    ov.graphVarsIndexes.map { vix =>
      allMonitorStatesById.get(SMGMonVarState.stateId(ou, vix))
    }.collect { case Some(x) => x }
  }


  override def objectViewStates(ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonState]]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    val expadedObjs = ovs.map( ov => (ov.id, expandOv(ov))).toMap
    val byRemote = expadedObjs.values.flatten.toSeq.groupBy(ov => SMGRemote.remoteId(ov.id))
    val futs = byRemote.map{ case (rmtId, myOvs) =>
      if (rmtId == SMGRemote.local.id) {
        Future {
          ovs.map { ov => (ov.id, localNonAgObjectStates(ov)) }.toMap
        }
      } else {
        remotes.objectViewsStates(rmtId, myOvs)
      }
    }
    Future.sequence(futs).map { maps =>
      val nonAgsMap = if (maps.isEmpty) {
        log.error("objectViewStates: maps.isEmpty")
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

  override def localProblems(includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Seq[SMGMonState] = {

    // helper to check whether the given mon state matches the supplied arguments criteria
    def myMonStateFilter(ms: SMGMonState): Boolean = {
      ((ms.currentStateVal != SMGState.OK) &&
        (includeSoft || ms.isHard) &&
        (includeAcked || !ms.isAcked) &&
        (includeSilenced || !ms.isSilenced)) ||
        (ms.currentStateVal == SMGState.OK && includeSilenced && ms.isSilenced)
    }

    val allProblems = allMonitorStatesById.filter { case (stateId, monState) =>
      myMonStateFilter(monState)
    }

    val topLevelProblemsBySeverity = allProblems.values.filter { monState =>
      monState.parentId.isEmpty || {
        val parentProblem = allProblems.get(monState.parentId.get)
        parentProblem.isEmpty || parentProblem.get.isInstanceOf[SMGMonRunState]
      }
    }.groupBy(_.currentStateVal)

    topLevelProblemsBySeverity.keys.toSeq.sortBy(-_.id).flatMap(sv => topLevelProblemsBySeverity(sv))
  }

  override def problems(includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Future[Seq[(SMGRemote, Seq[SMGMonState])]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    val remoteFuts = configSvc.config.remotes.map { rmt =>
      remotes.monitorIssues(rmt.id, includeSoft, includeAcked, includeSilenced).map((rmt,_))
    }
    val localFut = Future {
      (SMGRemote.local, localProblems(includeSoft, includeAcked, includeSilenced))
    }
    val allFuts = Seq(localFut) ++ remoteFuts
    Future.sequence(allFuts)
  }

  def localFetchState(cmdId: String): Option[SMGMonState] = {
    // TODO validate that result is prefetch?
    allMonitorStatesById.get(cmdId)
  }

  override def fetchCommandState(cmdId: String): Future[Option[SMGMonState]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    if (SMGRemote.isRemoteObj(cmdId)){
      remotes.monitorFetchCommandState(cmdId)
    } else Future {
      localFetchState(cmdId)
    }
  }

  def silenceLocalObject(msid:String, action: SMGMonSilenceAction):Boolean = {
    val monState = allMonitorStatesById.get(msid)
    if (monState.isDefined) {
      val sendAckMsgs = action.silence && (action.action == SMGMonSilenceAction.ACK || action.action == SMGMonSilenceAction.ACK_PF)
      monState.get.silence(action)
      // send acknowledgemenet notifications if applicable
      if (sendAckMsgs)
        notifSvc.sendAcknowledgementMessages(monState.get)

      val monTree = allMonitorSateTreesById.get(monState.get.id)
      if (monTree.isDefined) {
        monTree.get.allNodes.tail.foreach { t =>
          t.silence(action)
          if (sendAckMsgs)
            notifSvc.sendAcknowledgementMessages(t)
        }
      }
      true
    } else {
      false
    }
  }

  def silenceObject(ouid:String, action: SMGMonSilenceAction): Future[Boolean] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    if (SMGRemote.isRemoteObj(ouid)) {
      remotes.monitorSilence(ouid, action)
    } else {
      Future {
        silenceLocalObject(ouid, action)
      }
    }
  }

  def silenceFetchCommand(cmdId: String, until: Option[Int]): Future[Boolean] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    if (SMGRemote.isRemoteObj(cmdId)) {
      remotes.monitorSilenceFetchCommand(cmdId, until)
    } else {
      Future {
        val conf = configSvc.config
        val (slncValOpt, pfCmd) = if (conf.preFetches.contains(cmdId)) {
          (Some(SMGMonSilenceAction.SILENCE_PF), conf.preFetches.get(cmdId))
        } else if (conf.updateObjectsById.contains(cmdId)) {
          (Some(SMGMonSilenceAction.SILENCE), None)
        } else {
          log.error(s"SMGMonitor.silenceFetchCommand: Invalid fetch command id supplied: $cmdId")
          (None, None)
        }
        if (slncValOpt.isDefined) {
          val slncAction = SMGMonSilenceAction(slncValOpt.get, silence = true, until)
          silenceLocalObject(cmdId, slncAction)
          // XXX TODO temp hack to support monstate id which is pfId:interval
          if (pfCmd.isDefined)
            conf.intervals.foreach( i => silenceLocalObject(SMGMonPfState.stateId(pfCmd.get, i), slncAction))
          true
        } else {
          false
        }
      }
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

  override def localHeatmap(flt: SMGFilter, maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): SMGMonHeatmap = {
    // TODO include global/run issues?
    val objList = smg.getFilteredObjects(flt).filter(o => SMGRemote.isLocalObj(o.id)) // XXX or clone the filter with empty remote?
    val objsSlice = objList.slice(offset.getOrElse(0), offset.getOrElse(0) + limit.getOrElse(objList.size))
    val allStates = objsSlice.flatMap( ov => localNonAgObjectStates(ov))
    val ct = if (maxSize.isDefined && allStates.nonEmpty) condenseHeatmapStates(allStates, maxSize.get) else (allStates.toList, 1)
    SMGMonHeatmap(ct._1, ct._2)
  }

  override def heatmap(flt: SMGFilter, maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[Seq[(SMGRemote, SMGMonHeatmap)]] = {
    implicit val ec = ExecutionContexts.rrdGraphCtx
    val myRemotes = configSvc.config.allRemotes.filter { rmt =>
      val fltRemoteId = flt.remote.getOrElse(SMGRemote.local.id)
      (fltRemoteId == SMGRemote.wildcard.id) || (rmt.id == fltRemoteId)
    }
    val futs = myRemotes.map { rmt =>
      if (rmt == SMGRemote.local)
        Future {
          (rmt, localHeatmap(flt, maxSize, offset, limit))
        }
      else
        remotes.heatmap(rmt.id, flt, maxSize, offset, limit).map(mh => (rmt, mh))

    }
    Future.sequence(futs)
  }

  def inspectStateTree(stateId: String): Option[String] = {
    allMonitorSateTreesById.get(stateId).map { mst =>
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
    allMonitorStatesById.grouped(MAX_STATES_PER_CHUNK).map { chunk =>
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

  lifecycle.addStopHook { () =>
    Future.successful {
      saveStateToDisk()
    }
  }
  loadStateFromDisk()
}
