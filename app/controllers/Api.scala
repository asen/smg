package controllers

import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import com.smule.smg._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future

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
                      notifyApi: SMGMonNotifyApi
                     )  extends Controller {


  val log = SMGLogger

  import SMGRemoteClient._

  /**
    * Reload local config (do not propagate to other remotes)
    *
    * @return
    */
  def reloadLocal = Action {
    configSvc.reload()
    remotes.fetchConfigs()
    Ok("OK")
  }

  def reloadSlave(slaveId: String) = Action {
    remotes.fetchSlaveConfig(slaveId)
    Ok("OK")
  }

  /**
    * Fetch the entire remote config as json object.
    *
    * @return
    */
  def config = Action {
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
            fnan: Option[String]): Action[AnyContent] = Action.async {
    val obj = smg.getObjectView(oid)
    if (obj.isEmpty) Future {
      Ok("[]")
    }
    else {
      val params = SMGRrdFetchParams(r, s, e, filterNan = fnan.getOrElse("false") == "true")
      smg.fetch(obj.get, params).map { ret =>
        val json = Json.toJson(ret)
        Ok(json)
      }
    }
  }

  def fetchMany(ids: String,
            r: Option[Int],
            s: Option[String],
            e: Option[String],
            fnan: Option[String]): Action[AnyContent] = Action.async {
    val oids = ids.split(",")
    if (oids.isEmpty) Future {
      Ok("[]")
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
               r: Option[Int],
               s: Option[String],
               e: Option[String],
               fnan: Option[String]): Action[AnyContent] = Action.async {
    val idLst = ids.split(',').toList
    val objList = idLst.filter(id => smg.getObjectView(id).nonEmpty).map(id => smg.getObjectView(id).get)
    if (objList.isEmpty)
      Future {}.map { _ => NotFound("object ids not found") }
    else {
      val aobj = SMGAggObjectView.build(objList, op)
      val params = SMGRrdFetchParams(r, s, e, filterNan = fnan.getOrElse("false") == "true")
      smg.fetchAgg(aobj, params).map { ret =>
        val json = Json.toJson(ret)
        Ok(json)
      }
    }
  }

  private def goptsFromParams(params: Map[String, Seq[String]]): GraphOptions = {
    val step = params.get("step").map(_.head.toInt)
    val pl = params.get("pl").map(_.head.toString)
    val disablePop = params.get("dpp").exists(_.head == "on")
    val disable95p = params.get("d95p").exists(_.head == "on")
    val maxY = params.get("maxy").map(_.head.toDouble)
    val minY = params.get("miny").map(_.head.toDouble)
    GraphOptions(step = step, pl = pl, xsort = None,
      disablePop = disablePop, disable95pRule = disable95p,
      maxY = maxY, minY = minY)
  }

  /**
    * Graph a list of object ids and return the images metadata as JSON
    *
    * @return
    */
  def graph: Action[AnyContent] = Action.async { request =>
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
  def agg: Action[AnyContent] = Action.async { request =>
    val params = request.body.asFormUrlEncoded.get
    val gopts = goptsFromParams(params)
    aggCommon(params("ids").head, params("op").head, params("periods").headOption,
      gopts, params("title").headOption).map { imgLst: Seq[SMGImageView] =>
      val json = Json.toJson(imgLst)
      Ok(json)
    }
  }

  /**
    * Helper called by graphAgg after POST params have been parsed
    *
    * @param idsStr     - comma separated list of object ids to aggregate
    * @param op         - aggregate operation
    * @param periodsStr - comma separated list of periods to graph
    * @param title      - optional graph title
    * @return
    */
  def aggCommon(idsStr: String, op: String, periodsStr: Option[String], gopts: GraphOptions, title: Option[String]): Future[Seq[SMGImageView]] = {
    val ids = idsStr.split(',').toList
    val periods = periodsStr match {
      case Some(s) => s.split(',').toList
      case None => smg.detailPeriods
    }
    val objsById = configSvc.config.viewObjectsById
    val lst = ids.map(oid => objsById.get(oid)).filter(o => o.nonEmpty).map(o => o.get)
    if (lst.nonEmpty) {
      val aggObj = SMGAggObjectView.build(lst, op, title)
      smg.graphAggObject(aggObj, periods, gopts, xRemote = false)
    } else Future {
      Seq()
    }
  }

  def downloadRrd(oid: String) = Action {
    val obj = smg.getObjectView(oid)
    if (obj.isDefined && obj.get.rrdFile.isDefined) {
      Ok.sendFile(new java.io.File(obj.get.rrdFile.get))
    } else {
      NotFound("object id not found")
    }
  }

  def pluginData(pluginId: String) = Action { request =>
    val httpParams = request.queryString.map { case (k, v) => k -> v.mkString }
    configSvc.plugins.find(p => p.pluginId == pluginId) match {
      case Some(plugin) => {
        Ok(plugin.rawData(httpParams))
      }
      case None => NotFound("")
    }
  }

  private def idsToObjectViews(idsStr: String) = {
    val ids = idsStr.split(',').toList
    val objsById = configSvc.config.viewObjectsById
    ids.map(oid => objsById.get(oid)).filter(o => o.nonEmpty).map(o => o.get)
  }

  def monitorLog(period: Option[String], limit: Option[Int], sev: Option[String], soft: Option[String], ackd: Option[String], slncd: Option[String]) = Action {
    val minSev = sev.map{ s => SMGState.withName(s) }
    val logs = monitorApi.monLogApi.getLocal(period.getOrElse("24h"),
      limit.getOrElse(100), minSev, soft.getOrElse("off") == "on",
      ackd.getOrElse("off") == "on", slncd.getOrElse("off") == "on")
    Ok(Json.toJson(logs))
  }
  
  def monitorStates(ms: Option[String], soft: Option[String], ackd: Option[String], slncd: Option[String]) = Action {
    val myMs = ms.map(s => SMGState.withName(s)).getOrElse(SMGState.E_ANOMALY)
    val flt = SMGMonFilter(rx = None, rxx = None, minState = Some(myMs),
      includeSoft =  soft.getOrElse("off") == "on", includeAcked = ackd.getOrElse("off") == "on",
      includeSilenced = slncd.getOrElse("off") == "on"
    )
    val states = monitorApi.localStates(flt, includeInherited = false)
    Ok(Json.toJson(SMGMonitorStatesResponse(SMGRemote.local, states, isMuted = notifyApi.isMuted)))
  }

  def monitorSilencedStates()  = Action {
    val states = monitorApi.localSilencedStates()
    Ok(Json.toJson(states))
  }

  def monitorHeatmap = Action { request =>
    monitorHeatmapCommon(request.queryString)
  }

  def monitorHeatmapPost = Action { request =>
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

  def monitorObjectViewsPost: Action[AnyContent] = Action.async { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head
    monitorObjectViewsCommon(ids)
  }

  def monitorObjectViewsGet(idsStr: String): Action[AnyContent] = Action.async {
    monitorObjectViewsCommon(idsStr)
  }

  def monitorObjectViewsCommon(idsStr: String): Future[Result] = {
    val lst = idsToObjectViews(idsStr)
    monitorApi.objectViewStates(lst).map { mss =>
      val json = Json.toJson(mss)
      Ok(json)
    }
  }

  def monitorRunTree(root: Option[String]) = Action {
    val trees = configSvc.config.fetchCommandTreesWithRoot(root)
    Ok(Json.toJson(trees.map(t => (t._1.toString, Json.toJson(t._2)))))
  }

  def monitorTrees(rx: Option[String],
                   rxx: Option[String],
                   ms: Option[String],
                   soft: Option[String],
                   ackd: Option[String],
                   slncd: Option[String],
                   rid: Option[String],
                   pg: Int,
                   lmt: Int) = Action {
    val flt = SMGMonFilter(rx, rxx, ms.map(s => SMGState.withName(s)),
      includeSoft = soft.getOrElse("off") == "on", includeAcked = ackd.getOrElse("off") == "on",
      includeSilenced = slncd.getOrElse("off") == "on")
    val tpl = monitorApi.localMonTrees(flt, rid, pg, lmt)
    val m = Map("seq" -> Json.toJson(tpl._1), "maxpg" -> Json.toJson(tpl._2))
    Ok(Json.toJson(m))
  }


  def monitorSilenceAllTrees(rx: Option[String],
                             rxx: Option[String],
                             ms: Option[String],
                             soft: Option[String],
                             ackd: Option[String],
                             slncd: Option[String],
                             rid: Option[String],
                             until: Int): Action[AnyContent] = Action.async {
    val flt = SMGMonFilter(rx, rxx, ms.map(s => SMGState.withName(s)),
      includeSoft = soft.getOrElse("off") == "on", includeAcked = ackd.getOrElse("off") == "on",
      includeSilenced = slncd.getOrElse("off") == "on")
    monitorApi.silenceAllTrees(SMGRemote.local.id, flt, rid, until).map { ret =>
      if (ret)
        Ok("")
      else
        NotFound("Some error occured")
    }
  }


  def monitorAck(id: String): Action[AnyContent] = Action.async {
    monitorApi.acknowledge(id).map { b =>
      if (b)
        Ok("OK")
      else
        NotFound("state id not found")
    }
  }

  def monitorUnack(id: String): Action[AnyContent] = Action.async {
    monitorApi.unacknowledge(id).map { b =>
      if (b)
        Ok("OK")
      else
        NotFound("state id not found")
    }
  }

  def monitorSilence(id: String, slunt: Int): Action[AnyContent] = Action.async {
    monitorApi.silence(id, slunt).map { b =>
      if (b)
        Ok("OK")
      else
        NotFound("state id not found")
    }
  }

  def monitorUnsilence(id: String): Action[AnyContent] = Action.async {
    monitorApi.unsilence(id).map { b =>
      if (b)
        Ok("OK")
      else
        NotFound("state id not found")
    }
  }

  def monitorAckList: Action[AnyContent] = Action { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head.split(",")
    if (monitorApi.acknowledgeListLocal(ids))
      Ok("OK")
    else
      NotFound("silenceListLocal returned false")
  }

  def monitorSilenceList: Action[AnyContent] = Action { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head.split(",")
    val slunt = params("slunt").head.toInt
    if (monitorApi.silenceListLocal(ids, slunt))
      Ok("OK")
    else
      NotFound("silenceListLocal returned false")
  }

  def monitorMute(): Action[AnyContent] = Action {
    notifyApi.muteAll()
    Ok("OK")
  }

  def monitorUnmute(): Action[AnyContent] = Action {
    notifyApi.unmuteAll()
    Ok("OK")
  }

}
