package controllers

import akka.actor.ActorSystem
import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core._
import com.smule.smg.grapher.{GraphOptions, SMGAggObjectView, SMGImageView}
import com.smule.smg.monitor._
import com.smule.smg.notify.SMGMonNotifyApi
import com.smule.smg.remote.{SMGRemotesApi, _}
import com.smule.smg.rrd.SMGRrdFetchParams
import controllers.actions.SystemAction

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Created by asen on 11/19/15.
  *
  * Controller serving JSON to remote SMG instance
  */
@Singleton
class Api  @Inject() (actorSystem: ActorSystem,
                      smg: GrapherApi,
                      remotes: SMGRemotesApi,
                      configSvc: SMGConfigService,
                      monitorApi: SMGMonitorApi,
                      notifyApi: SMGMonNotifyApi,
                      systemAction: SystemAction
                     )(implicit ec: ExecutionContext)  extends InjectedController {


  val log = SMGLogger

  import com.smule.smg.remote.SMGRemoteClient._

  /**
    * Reload local config (do not propagate to other remotes)
    *
    * @return
    */
  def reloadLocal: Action[AnyContent] = systemAction.rootAction {
    configSvc.reloadLocal()
    remotes.fetchConfigs()
    Ok("OK")
  }

  // TODO - allow from the actual slave host?
  def reloadSlave(slaveId: String): Action[AnyContent] = systemAction.adminAction {
    remotes.fetchSlaveConfig(slaveId)
    Ok("OK")
  }

  /**
    * Fetch the entire remote config as json object.
    *
    * @return
    */
  def config: Action[AnyContent] = systemAction.viewAction {
    val json = Json.toJson(configSvc.config)
    Ok(json)
  }

  /**
    * Fetch rrd rows for a non-aggregate object as json
    *
    * @param oid
    * @param r
    * @param s
    * @param e
    * @return
    */
  def fetch(oid: String,
            r: Option[Int],
            s: Option[String],
            e: Option[String],
            fnan: Option[String]): Action[AnyContent] = systemAction.viewAction.async {
    val obj = smg.getObjectView(oid)
    if (obj.isEmpty) Future {
      Ok("[]")
    }
    else {
      val pl = if (e.getOrElse("") == "") None else e
      val params = SMGRrdFetchParams(r, s, pl, filterNan = fnan.getOrElse("false") == "true")
      smg.fetch(obj.get, params).map { ret =>
        val json = Json.toJson(ret)
        Ok(json)
      }
    }
  }


  def fetchManyCommon(ids: String,
                r: Option[Int],
                s: Option[String],
                e: Option[String],
                fnan: Option[String]): Future[Result] ={
    val oids = ids.split(",")
    if (oids.isEmpty) Future {
      NotFound("{}")
    }
    else {
      val params = SMGRrdFetchParams(r, s, e, filterNan = fnan.getOrElse("false") == "true")
      val objs = oids.map(id => smg.getObjectView(id)).filter(_.isDefined).map(_.get)
      smg.fetchMany(objs, params).map { ret =>
        val json = Json.toJson(ret.toMap)
        Ok(json)
      }
    }
  }

  def fetchMany(ids: String,
            r: Option[Int],
            s: Option[String],
            e: Option[String],
            fnan: Option[String]): Action[AnyContent] = systemAction.viewAction.async {
    fetchManyCommon(ids, r, s, e, fnan)
  }

  def fetchManyPost(): Action[AnyContent] = systemAction.viewAction.async { request =>
    val params = request.body.asFormUrlEncoded.get
    fetchManyCommon(params("ids").head,
      params.get("r").map(_.head.toInt),
      params.get("s").map(_.head),
      params.get("e").map(_.head),
      params.get("fnan").map(_.head))
  }

  def fetchAggCommon(ids: String,
                     op: String,
                     gb: Option[String],
                     gbp : Option[String],
                     r: Option[Int],
                     s: Option[String],
                     e: Option[String],
                     fnan: Option[String]): Future[Result] = {
    val idLst = ids.split(',').toList
    val objList = idLst.filter(id => smg.getObjectView(id).nonEmpty).map(id => smg.getObjectView(id).get)
    if (objList.isEmpty)
      Future {}.map { _ => NotFound("object ids not found") }
    else {
      val groupBy = SMGAggGroupBy.gbParamVal(gb)
      val aobj = SMGAggObjectView.build(objList, op, groupBy, gbp, None)
      val params = SMGRrdFetchParams(r, s, e, filterNan = fnan.getOrElse("false") == "true")
      smg.fetchAgg(aobj, params).map { ret =>
        val json = Json.toJson(ret)
        Ok(json)
      }
    }
  }

  /**
    * Fetch rrd rows for an aggregate object as json
    *
    * @param ids
    * @param op
    * @param r
    * @param s
    * @param e
    * @return
    */
  def fetchAgg(ids: String,
               op: String,
               gb: Option[String],
               gbp: Option[String],
               r: Option[Int],
               s: Option[String],
               e: Option[String],
               fnan: Option[String]): Action[AnyContent] = systemAction.viewAction.async {
    fetchAggCommon(ids, op, gb, gbp, r, s, e, fnan)
  }

  def fetchAggPost(): Action[AnyContent] = systemAction.viewAction.async { request =>
    val params = request.body.asFormUrlEncoded.get
    fetchAggCommon(params("ids").head,
      params("op").head,
      params.get("gb").map(_.head),
      params.get("gbp").map(_.head),
      params.get("r").map(_.head.toInt),
      params.get("s").map(_.head),
      params.get("e").map(_.head),
      params.get("fnan").map(_.head))
  }

  private def goptsFromParams(params: Map[String, Seq[String]]): GraphOptions = {
    val step = params.get("step").map(_.head.toInt)
    val pl = params.get("pl").map(_.head.toString)
    val disablePop = params.get("dpp").exists(_.head == "on")
    val disable95p = params.get("d95p").exists(_.head == "on")
    val maxY = params.get("maxy").map(_.head.toDouble)
    val minY = params.get("miny").map(_.head.toDouble)
    val logY = params.get("logy").exists(_.head == "on")
    GraphOptions(step = step, pl = pl, xsort = None,
      disablePop = disablePop, disable95pRule = disable95p,
      maxY = maxY, minY = minY, logY = logY)
  }

  /**
    * Graph a list of object ids and return the images metadata as JSON
    *
    * @return
    */
  def graph: Action[AnyContent] = systemAction.viewAction.async { request =>
    val params = request.body.asFormUrlEncoded.get
    val gopts = goptsFromParams(params)
    graphCommon(params("ids").head, params("periods").headOption, gopts).map { imgLst: Seq[SMGImageView] =>
      val json = Json.toJson(imgLst)
      Ok(json)
    }
  }

  /**
    * Helper to called by graph after POST params have been parsed
    *
    * @param idsStr     - comma separated list of object ids
    * @param periodsStr - comma separated list of periods
    * @return
    */
  def graphCommon(idsStr: String, periodsStr: Option[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    val ids = idsStr.split(',').toList
    val periods = periodsStr match {
      case Some(s) => s.split(',').toList
      case None => smg.detailPeriods
    }
    val objsById = configSvc.config.viewObjectsById
    val lst = ids.map(oid => objsById.get(oid)).filter(o => o.nonEmpty).map(o => o.get)
    smg.graphObjects(lst, periods, gopts)
  }

  /**
    * Draw an aggregate image from supplied list of object ids and operation in a set of supplied periods.
    *
    * @return
    */
  def agg: Action[AnyContent] = systemAction.viewAction.async { request =>
    val params = request.body.asFormUrlEncoded.get
    val gopts = goptsFromParams(params)
    aggCommon(params("ids").head, params("op").head, params.get("gb").map(_.head),
      params.get("gbp").map(_.head),
      params("periods").headOption,
      gopts, params("title").headOption).map { imgLst: Seq[SMGImageView] =>
      val json = Json.toJson(imgLst)
      Ok(json)
    }
  }

  /**
    * Helper called by graphAgg after POST params have been parsed
    *
    * @return
    */
  def aggCommon(idsStr: String, op: String, gb: Option[String], gbParam: Option[String],
                periodsStr: Option[String], gopts: GraphOptions, title: Option[String]): Future[Seq[SMGImageView]] = {
    val ids = idsStr.split(',').toList
    val periods = periodsStr match {
      case Some(s) => s.split(',').toList
      case None => smg.detailPeriods
    }
    val objsById = configSvc.config.viewObjectsById
    val lst = ids.map(oid => objsById.get(oid)).filter(o => o.nonEmpty).map(o => o.get)
    if (lst.nonEmpty) {
      val groupBy = SMGAggGroupBy.gbParamVal(gb)
      val aggObj = SMGAggObjectView.build(lst, op, groupBy, gbParam, title)
      smg.graphAggObject(aggObj, periods, gopts, xRemote = false)
    } else Future {
      log.error(s"Api.aggCommon: No objects matching provided list: $ids")
      Seq()
    }
  }

  def downloadRrd(oid: String): Action[AnyContent] = systemAction.viewAction {
    val obj = smg.getObjectView(oid)
    if (obj.isDefined && obj.get.rrdFile.isDefined) {
      configSvc.config.rrdConf.flushRrdCachedFile(obj.get.rrdFile.get)
      Ok.sendFile(new java.io.File(obj.get.rrdFile.get))
    } else {
      NotFound("object id not found")
    }
  }

  def runCommandTree(interval: Int, id: String): Action[AnyContent] = systemAction.adminAction.async {
    smg.runCommandsTreeNow(interval, id).map { ret =>
      if (ret)
        Ok(s"OK - sent message for ${id}")
      else
        NotFound(s"ERROR - did not find commands tree with root ${id}")
    }
  }

  // TODO plugin/proxy auth
  def pluginData(pluginId: String): Action[AnyContent] = systemAction.viewAction { request =>
    val httpParams = request.queryString.map { case (k, v) => k -> v.mkString }
    configSvc.plugins.find(p => p.pluginId == pluginId) match {
      case Some(plugin) => {
        Ok(plugin.rawData(httpParams))
      }
      case None => NotFound("")
    }
  }

  private def idsToObjectViews(idsStr: String): List[SMGObjectView] = {
    val ids = idsStr.split(',').toList
    val objsById = configSvc.config.viewObjectsById
    ids.map(oid => objsById.get(oid)).filter(o => o.nonEmpty).map(o => o.get)
  }

  def monitorRerun(id: String): Action[AnyContent] = systemAction.adminAction.async { request =>
    val paramsOpt = request.body.asFormUrlEncoded
    val intervals : Seq[Int] = if (paramsOpt.isDefined && paramsOpt.get.contains("intvls")) {
      val params: Map[String, Seq[String]] = paramsOpt.get
      val ivsStr: String = params("intvls").headOption.getOrElse("")
      if (ivsStr == "")
        Seq[Int]()
      else {
        ivsStr.split(",").flatMap{s => Try(s.toInt).toOption }
      }
    } else Seq()
    monitorApi.monitorRerun(id, intervals).map { ret =>
      if (ret)
        Ok("Success")
      else
        NotFound("Some error occured (posibly state not found)")
    }
  }

  def monitorLog(period: Option[String], limit: Option[Int], sev: Option[String], soft: Option[String],
                 ackd: Option[String], slncd: Option[String],
                 rx: Option[String], rxx: Option[String]): Action[AnyContent] = systemAction.viewAction {
    val minSev = sev.map{ s => SMGState.fromName(s) }
    val flt = SMGMonitorLogFilter(
      periodStr = period.getOrElse("24h"),
      rmtIds = Seq(SMGRemote.local.id),
      limit = limit.getOrElse(100),
      minSeverity = minSev,
      inclSoft = soft.getOrElse("off") == "on",
      inclAcked = ackd.getOrElse("off") == "on",
      inclSilenced = slncd.getOrElse("off") == "on",
      rx = rx,
      rxx = rxx)
    val logs = monitorApi.monLogApi.getLocal(flt)
    Ok(Json.toJson(logs))
  }
  
  def monitorStates(ms: Option[String], soft: Option[String], ackd: Option[String],
                    slncd: Option[String]): Action[AnyContent] = systemAction.viewAction {
    val myMs = ms.map(s => SMGState.fromName(s)).getOrElse(SMGState.ANOMALY)
    val flt = SMGMonFilter(rx = None, rxx = None, minState = Some(myMs),
      includeSoft =  soft.getOrElse("off") == "on", includeAcked = ackd.getOrElse("off") == "on",
      includeSilenced = slncd.getOrElse("off") == "on"
    )
    val states = monitorApi.localStates(flt, includeInherited = false)
    Ok(Json.toJson(SMGMonitorStatesResponse(SMGRemote.local, states, isMuted = notifyApi.isMuted, notifyApi.getActiveAlerts)))
  }

  def monitorSilenced(): Action[AnyContent] = systemAction.viewAction {
    val states = monitorApi.localSilencedStates()
    Ok(
      Json.toJson(Map(
      "sts" -> Json.toJson(states._1),
      "sls" -> Json.toJson(states._2)
      ))
    )
  }

  def monitorHeatmap: Action[AnyContent] = systemAction.viewAction { request =>
    monitorHeatmapCommon(request.queryString)
  }

  def monitorHeatmapPost: Action[AnyContent] = systemAction.viewAction { request =>
    monitorHeatmapCommon(request.body.asFormUrlEncoded.get)
  }

  def monitorHeatmapCommon(params: Map[String, Seq[String]]): Result = {
    val flt = SMGFilter.fromParams(params)
    val ix = params.get("ix").map(_.head).flatMap { ixId =>
      smg.getIndexById(ixId)
    }
    val hm = monitorApi.localHeatmap(flt, ix,
      params.get("maxSize").map(_.head.toInt),
      params.get("offset").map(_.head.toInt),
      params.get("limit").map(_.head.toInt))
    Ok(Json.toJson(hm))
  }

  def monitorObjectViewsPost: Action[AnyContent] = systemAction.viewAction.async { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head
    monitorObjectViewsCommon(ids)
  }

  def monitorObjectViewsGet(idsStr: String): Action[AnyContent] = systemAction.viewAction.async {
    monitorObjectViewsCommon(idsStr)
  }

  def monitorObjectViewsCommon(idsStr: String): Future[Result] = {
    val lst = idsToObjectViews(idsStr)
    monitorApi.objectViewStates(lst).map { mss =>
      val json = Json.toJson(mss)
      Ok(json)
    }
  }

  def monitorRunTree(root: Option[String]): Action[AnyContent] = systemAction.viewAction {
    val trees = configSvc.config.getFetchCommandTreesWithRoot(root)
    Ok(Json.toJson(trees.map(t => (t._1.toString, Json.toJson(t._2)))))
  }

  def monitorTrees(rx: Option[String],
                   rxx: Option[String],
                   ms: Option[String],
                   soft: Option[String],
                   ackd: Option[String],
                   slncd: Option[String],
                   rid: Option[String],
                   lmt: Option[Int]): Action[AnyContent] = systemAction.viewAction {
    val flt = SMGMonFilter(rx, rxx, ms.map(s => SMGState.fromName(s)),
      includeSoft = soft.getOrElse("off") == "on", includeAcked = ackd.getOrElse("off") == "on",
      includeSilenced = slncd.getOrElse("off") == "on")
    val limit = lmt.getOrElse(configSvc.TREES_PAGE_DFEAULT_LIMIT)
    val trees = monitorApi.localMatchingMonTrees(flt, rid)
    val m = Map("seq" -> Json.toJson(trees.take(limit).map(_.asInstanceOf[SMGTree[SMGMonState]])), "total" -> Json.toJson(trees.size))
    Ok(Json.toJson(m))
  }

  def statesDetails(): Action[AnyContent] = systemAction.viewAction { implicit request =>
    val ids: Seq[String] = request.body.asJson.map(s => s.as[Seq[String]]).getOrElse(Seq())
    val mm = monitorApi.localStatesDetails(ids)
    Ok(Json.toJson(mm))
  }


  def monitorSilenceAllTrees(rx: Option[String],
                             rxx: Option[String],
                             ms: Option[String],
                             soft: Option[String],
                             ackd: Option[String],
                             slncd: Option[String],
                             rid: Option[String],
                             until: Int,
                             sticky: Option[String],
                             stickyDesc: Option[String]): Action[AnyContent] = systemAction.adminAction.async {
    val flt = SMGMonFilter(rx, rxx, ms.map(s => SMGState.fromName(s)),
      includeSoft = soft.getOrElse("off") == "on", includeAcked = ackd.getOrElse("off") == "on",
      includeSilenced = slncd.getOrElse("off") == "on")
    val stickyB = sticky.getOrElse("off") == "on"
    monitorApi.silenceAllTrees(Seq(SMGRemote.local.id), flt, rid, until, stickyB, stickyDesc).map { ret =>
      if (ret)
        Ok("")
      else
        NotFound("Some error occured")
    }
  }

  def removeStickySilence(): Action[AnyContent] = systemAction.adminAction.async { request =>
    val uuid = request.body.asFormUrlEncoded.getOrElse(Map()).get("uid").map(_.head)
    if (uuid.isDefined) {
      monitorApi.removeStickySilence(uuid.get).map { b =>
        if (b) {
          Ok("success")
        } else {
          NotFound("removeStickySilence returned false")
        }
      }
    } else {
      Future {
        NotFound("missing uid parameter")
      }
    }
  }

  def monitorAck(id: String): Action[AnyContent] = systemAction.adminAction.async {
    monitorApi.acknowledge(id).map { b =>
      if (b)
        Ok("OK")
      else
        NotFound("state id not found")
    }
  }

  def monitorUnack(id: String): Action[AnyContent] = systemAction.adminAction.async {
    monitorApi.unacknowledge(id).map { b =>
      if (b)
        Ok("OK")
      else
        NotFound("state id not found")
    }
  }

  def monitorSilence(id: String, slunt: Int): Action[AnyContent] = systemAction.adminAction.async {
    monitorApi.silence(id, slunt).map { b =>
      if (b)
        Ok("OK")
      else
        NotFound("state id not found")
    }
  }

  def monitorUnsilence(id: String): Action[AnyContent] = systemAction.adminAction.async {
    monitorApi.unsilence(id).map { b =>
      if (b)
        Ok("OK")
      else
        NotFound("state id not found")
    }
  }

  def monitorAckList: Action[AnyContent] = systemAction.adminAction { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head.split(",")
    if (monitorApi.acknowledgeListLocal(ids))
      Ok("OK")
    else
      NotFound("silenceListLocal returned false")
  }

  def monitorSilenceList: Action[AnyContent] = systemAction.adminAction { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head.split(",")
    val slunt = params("slunt").head.toInt
    if (monitorApi.silenceListLocal(ids, slunt))
      Ok("OK")
    else
      NotFound("silenceListLocal returned false")
  }

  def monitorMute(): Action[AnyContent] = systemAction.adminAction {
    notifyApi.muteAll()
    Ok("OK")
  }

  def monitorUnmute(): Action[AnyContent] = systemAction.adminAction {
    notifyApi.unmuteAll()
    Ok("OK")
  }

  def monitorAlertConds(): Action[AnyContent] = systemAction.viewAction {
    val ret = configSvc.config.alertCondsSummary
    Ok(Json.toJson(ret))
  }

  def monitorNotifyCmds(): Action[AnyContent] = systemAction.viewAction {
    val ret = configSvc.config.notifyCmdsSummary
    Ok(Json.toJson(ret))
  }
}
