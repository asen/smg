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
                      monitorApi: SMGMonitorApi
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
  def fetch(oid: String, r: Option[Int], s: Option[String], e: Option[String], fnan: Option[String]) = Action.async {
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
  def fetchAgg(ids: String, op: String, r: Option[Int], s: Option[String], e: Option[String], fnan: Option[String]) = Action.async {
    val idLst = ids.split(',').toList
    val objList = idLst.filter(id => smg.getObjectView(id).nonEmpty).map(id => smg.getObjectView(id).get)
    if (objList.isEmpty)
      Future {}.map { _ => NotFound("object ids not found") }
    else {
      val aobj = SMGAggObject.build(objList, op)
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
    GraphOptions(step = step, pl = pl, disablePop = disablePop, disable95pRule = disable95p, maxY = maxY)
  }

  /**
    * Graph a list of object ids and return the images metadata as JSON
    *
    * @return
    */
  def graph = Action.async { request =>
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
  def agg = Action.async { request =>
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
      val aggObj = SMGAggObject.build(lst, op, title)
      smg.graphAggObject(aggObj, periods, gopts, false)
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

  def monitorLog(p: Option[String], l: Option[Int], soft: Option[String]) = Action {
    val logs = monitorApi.monLogApi.getLocal(p.getOrElse("24h"), l.getOrElse(100), soft.getOrElse("off") == "off")
    Ok(Json.toJson(logs))
  }

  def monitorIssues(soft: Option[String], ack: Option[String], slc: Option[String]) = Action {
    val inclSoft = soft.getOrElse("off") == "on"
    val inclAck = ack.getOrElse("off") == "on"
    val inclSlc = slc.getOrElse("off") == "on"
    val probs = monitorApi.localProblems(inclSoft, inclAck, inclSlc)
    Ok(Json.toJson(probs))
  }

  def monitorHeatmap = Action { request =>
    monitorHeatmapCommon(request.queryString)
  }

  def monitorHeatmapCommon(params: Map[String, Seq[String]]) = {
    val flt = SMGFilter.fromParams(params)
    val hm = monitorApi.localHeatmap(flt,
      params.get("maxSize").map(_.head.toInt),
      params.get("offset").map(_.head.toInt),
      params.get("limit").map(_.head.toInt))
    Ok(Json.toJson(hm))
  }

  def monitorObjectViewsPost = Action.async { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head
    monitorObjectViewsCommon(ids)
  }

  def monitorObjectViewsGet(idsStr: String) = Action.async {
    monitorObjectViewsCommon(idsStr)
  }

  def monitorObjectViewsCommon(idsStr: String) = {
    val lst = idsToObjectViews(idsStr)
    monitorApi.objectViewStates(lst).map { mss =>
      val json = Json.toJson(mss)
      Ok(json)
    }
  }

  def monitorSilence(oid: String, act: String, slnc: Option[String], unt: Option[Int]) =  Action.async {
    val actv = SMGMonSilenceAction.withName(act)
    val silence = slnc.getOrElse("on") == "on"
    monitorApi.silenceObject(oid, SMGMonSilenceAction(actv, silence, unt)).map{ b =>
      Ok(b.toString)
    }
  }

}
