package com.smule.smg

import java.io.{File, FileWriter}
import java.net.URL
import java.nio.file.{Files, StandardCopyOption}

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.ws.WSClient

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.language.postfixOps
import scala.sys.process._
import scala.util.Try

/**
  * Created by asen on 11/19/15.
  */
/**
  * json client for remote SMG API
  *
  * @param remote - remote definition
  * @param ws - Play WS client
  */
class SMGRemoteClient(val remote: SMGRemote, ws: WSClient, configSvc: SMGConfigService) {

  private val log = SMGLogger

  val API_PREFIX = "/api/"

  private def prefixedId(id: String) = SMGRemote.prefixedId(remote.id, id)
  private def toLocalId(id:String) = SMGRemote.localId(id)

  private def remoteUrl(urlPath: String) = if (configSvc.config.proxyDisable)
    remote.url + urlPath
  else
    s"/proxy/${remote.id}$urlPath"


  // TODO configure these per remote
  val shortTimeoutMs = 30000L
  val graphTimeoutMs = 60000L
  val configFetchTimeoutMs = 600000


  // Object deserializers (reads) used by API client below

  val nonAggObjectBuilder = (JsPath \ "id").read[String].map(id => prefixedId(id)) and
    (JsPath \ "interval").read[Int] and
    (JsPath \ "vars").read[List[Map[String, String]]] and
    (JsPath \ "cdefVars").readNullable[List[Map[String, String]]].map(ol => ol.getOrElse(List())) and
    (JsPath \ "graphVarsIndexes").readNullable[List[Int]].map(ol => ol.getOrElse(List())) and
    (JsPath \ "title").read[String].map(title => "(" + remote.id + ") " + title) and
    (JsPath \ "stack").read[Boolean]

  val nonAggObjectReads = nonAggObjectBuilder.apply(SMGRemoteObject.apply _)

  val aggObjectBuilder = {
    implicit val naor = nonAggObjectReads
    (JsPath \ "id").read[String].map(id => prefixedId(id)) and
      (JsPath \ "objs").read[List[SMGRemoteObject]] and
      (JsPath \ "op").read[String] and
      (JsPath \ "vars").read[List[Map[String, String]]] and
      (JsPath \ "cdefVars").readNullable[List[Map[String, String]]].map(ol => ol.getOrElse(List())) and
      (JsPath \ "graphVarsIndexes").readNullable[List[Int]].map(ol => ol.getOrElse(List())) and
      (JsPath \ "title").read[String].map(title => "(" + remote.id + ") " + title)
  }

  val aggObjectReads = aggObjectBuilder.apply(SMGRemoteAggObject.apply _)

  implicit val smgRemoteObjectViewReads: Reads[SMGObjectView] = {
    aggObjectBuilder.apply(SMGRemoteAggObject.apply _) or nonAggObjectBuilder.apply(SMGRemoteObject.apply _)
  }

  implicit val smgGraphOptionsReads: Reads[GraphOptions] = (
    (JsPath \ "step").readNullable[Int] and
      (JsPath \ "pl").readNullable[String] and
      (JsPath \ "xsort").readNullable[Int] and
      (JsPath \ "dpp").readNullable[String].map(xaggs => xaggs.getOrElse("") == "true") and
      (JsPath \ "d95p").readNullable[String].map(xaggs => xaggs.getOrElse("") == "true") and
      (JsPath \ "maxy").readNullable[Double]
    )(GraphOptions.apply _)

  implicit val smgFilterReads: Reads[SMGFilter] = (
    (JsPath \ "px").readNullable[String] and
      (JsPath \ "sx").readNullable[String] and
      (JsPath \ "rx").readNullable[String] and
      (JsPath \ "rxx").readNullable[String] and
      (JsPath \ "trx").readNullable[String] and
      (JsPath \ "remote").readNullable[String].map { remoteId => Some(remoteId.getOrElse(remote.id)) } and
      (JsPath \ "gopts").readNullable[GraphOptions].map(go => go.getOrElse(GraphOptions()))
    )(SMGFilter.apply _)


  implicit val smgConfIndexReads: Reads[SMGConfIndex] = (
    (JsPath \ "id").read[String].map(id => prefixedId(id)) and
      (JsPath \ "title").read[String] and
      (JsPath \ "flt").read[SMGFilter] and
      (JsPath \ "cols").readNullable[Int] and
      (JsPath \ "rows").readNullable[Int] and
      (JsPath \ "agg_op").readNullable[String] and
      (JsPath \ "xagg").readNullable[String].map(xaggs => xaggs.getOrElse("") == "true") and
      (JsPath \ "period").readNullable[String] and
      (JsPath \ "desc").readNullable[String] and
      (JsPath \ "parent").readNullable[String].map(os => if (os.nonEmpty) Some(prefixedId(os.get)) else None ) and
      (JsPath \ "children").readNullable[List[String]].map(_.getOrElse(List[String]())).
        map(ls => for (s <- ls) yield prefixedId(s)) and
      (JsPath \ "dhmap").readNullable[String].map(dhmaps => dhmaps.getOrElse("") == "true")
    )(SMGConfIndex.apply _)


  implicit val smgImageViewReads: Reads[SMGImageView] = {
    (
      (JsPath \ "obj").read[SMGObjectView] and
        (JsPath \ "period").read[String] and
        (JsPath \ "imageUrl").read[String].map(url =>  remoteUrl(url)) and
        Reads(v => JsSuccess(Some(remote.id)))
      )(SMGImage.apply _)
  }


  implicit val smgRrdRowReads: Reads[SMGRrdRow] = {
    (
      (JsPath \ "tss").read[Int] and
        (JsPath \ "vals").read[List[JsValue]].map(lst => lst.map( a => a.asOpt[Double].getOrElse(Double.NaN)))
      ) (SMGRrdRow.apply _)
  }

  implicit val smgStateReads: Reads[SMGState] = {
    //SMGState(ts: Int, state: SMGState.Value, desc: String)
    (
      (JsPath \ "ts").read[Int] and
        (JsPath \ "state").read[String].map(s => SMGState.withName(s)) and
        (JsPath \ "desc").read[String]
      ) (SMGState.apply _)
  }

  implicit val smgMonStateObjVarReads: Reads[SMGMonStateObjVar] = {
    (
       (
         JsPath \ "ouid").read[String].map(oid => prefixedId(oid) ) and
         (JsPath \ "ix").read[Int] and
         (JsPath \ "ovids").readNullable[List[String]].map(optLst => optLst.getOrElse(List()).map(ovid => prefixedId(ovid))) and
         (JsPath \ "pfid").readNullable[String] and
         (JsPath \ "title").read[String] and
         (JsPath \ "label").read[String] and
         (JsPath \ "ack").readNullable[Int].map(_.getOrElse(0) == 1) and
         (JsPath \ "slc").readNullable[Int].map(_.getOrElse(0) == 1) and
         (JsPath \ "sunt").readNullable[Int] and
         (JsPath \ "rs").read[List[SMGState]] and
         (JsPath \ "bs").readNullable[Int] and
         // XXX "remote" is always null ...
        (JsPath \ "remote").readNullable[String].map(s => remote)
      ) (SMGMonStateObjVar.apply _)
  }

  implicit val smgMonStateViewReads: Reads[SMGMonStateView] = {
    (
      (JsPath \ "s").read[Double] and
        (JsPath \ "t").read[String] and
        (JsPath \ "h").read[Int].map(o => o == 1) and
        (JsPath \ "a").readNullable[Int].map(o => o.getOrElse(0) == 1) and
        (JsPath \ "sl").readNullable[Int].map(o => o.getOrElse(0) == 1) and
        (JsPath \ "su").readNullable[Int] and
        (JsPath \ "o").readNullable[String].map(oid => oid.map(s => prefixedId(s))) and
        (JsPath \ "pf").readNullable[String].map(pfid => pfid.map(s => prefixedId(s))) and
        (JsPath \ "uf").readNullable[String] and
        (JsPath \ "v").read[String].map(s => SMGState.withName(s)) and
        (JsPath \ "er").readNullable[Int].map(oi => oi.getOrElse(0)) and // TODO temp readNullable
        (JsPath \ "bs").readNullable[Int] and
        // XXX "remote" is always null ...
        (JsPath \ "remote").readNullable[String].map(s => remote)
      ) (SMGMonStateView.apply _)
  }

  implicit val smgMonHeatmapReads: Reads[SMGMonHeatmap] = {
    //  case class SMGMonHeatmap(lst: List[SMGMonState], statesPerSquare: Int)
//    Json.obj(
//      "lst" -> mh.lst,
//      "sps" -> mh.statesPerSquare
//    )
    (
      (JsPath \ "lst").read[List[SMGMonStateView]] and
        (JsPath \ "sps").read[Int]
      ) (SMGMonHeatmap.apply _)
  }

  implicit val smgMonitorLogMsgReads: Reads[SMGMonitorLogMsg] = {
    (
      (JsPath \ "mlt").read[String].map(s => SMGMonitorLogMsg.withName(s)) and
        (JsPath \ "ts").read[Int] and
        (JsPath \ "msg").read[String] and
        (JsPath \ "rpt").read[Int] and
        (JsPath \ "hard").readNullable[String].map(hopt => hopt.getOrElse("") == "true") and
        (JsPath \ "ouids").readNullable[List[String]].map(ol => ol.getOrElse(Seq())) and
        (JsPath \ "vix").readNullable[Int] and
        // XXX "remote" is always null ...
        (JsPath \ "remote").readNullable[String].map(s => remote)
      ) (SMGMonitorLogMsg.apply _)
  }

  /**
    * Asynchronous call to /api/config to retrieve the configuration of the remote instance
    *
    * @return Future config or None if fetch failed
    */
  def fetchConfig: Future[Option[SMGRemoteConfig]] = {
    ws.url(remote.url + API_PREFIX + "config").withRequestTimeout(configFetchTimeoutMs).get().map { resp =>
      log.debug("SMGRemoteClient.fetchConfig: Received data from " + remote.id + " size=" + resp.body.length)
      val jsval = Json.parse(resp.body)
      val indexes = (jsval \ "indexes").as[Seq[SMGConfIndex]]
      try {
        SMGConfIndex.buildChildrenSubtree(indexes)
      } catch {
        case t: Throwable => {
          log.ex(t, "SMGRemoteClient.fetchConfig: Unexpected exception while building subtree (ignored)")
        }
      }
      try {
        val ret = SMGRemoteConfig(
          (jsval \ "globals").as[Map[String, String]],
          (jsval \ "objects").as[Seq[SMGObjectView]],
          indexes,
          remote
        )
        Some(ret)
      } catch {
        case t: Throwable => {
          log.ex(t, "SMGRemoteClient.fetchConfig: Unexpected exception while building SMGRemoteConfig (aborting)")
          None
        }
      }
    }.recover {
      case x => { log.ex(x, "remote config fetch error: " + remote.id); None }
    }
  }

  /**
    * Asynchronous call to POST /api/graph to request images for a list of object for a list of periods
    *
    * @param lst - list of objects to graph
    * @param periods - list of periods to graph the objects for
    * @return - Future sequence of SMG Images
    */
  def graphObjects(lst: Seq[SMGObjectView], periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    val periodStr: String = periods.mkString(",")
    val oids: String = lst.map(o => toLocalId(o.id)).mkString(",")
    val postMap = Map("ids" -> Seq(oids), "periods" -> Seq(periodStr)) ++ goptsMap(gopts)
    ws.url(remote.url + API_PREFIX + "graph").
      withRequestTimeout(graphTimeoutMs).post(postMap).map { resp =>
      val jsval = Json.parse(resp.body)
      jsval.as[Seq[SMGImageView]]
    }.recover {
      case x => {
        log.ex(x, "remote graph error: " + remote.id)
        lst.flatMap { ov => periods.map(p => SMGImage.errorImage(ov, p, Some(remote.id))) }
      }
    }
  }

  /**
    * Asynchronous call to POST /api/graphAgg to request images for an aggregate object for given periods
    *
    * @param aobj - aggregate object to graph
    * @param periods - list of periods to cover
    * @return - Future sequence of SMG Images
    */
  def graphAgg(aobj: SMGAggObjectView, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]] = {
    val periodStr: String = periods.mkString(",")
    val oids: String = aobj.objs.map(o => toLocalId(o.id)).mkString(",")
    val postMap = Map("ids" -> Seq(oids), "op" -> Seq(aobj.op),
      "title" -> Seq(aobj.title), "periods" -> Seq(periodStr)) ++ goptsMap(gopts)
    ws.url(remote.url + API_PREFIX + "agg").
      withRequestTimeout(graphTimeoutMs).post(postMap).map { resp =>
      val jsval = Json.parse(resp.body)
      jsval.as[Seq[SMGImageView]]
    }.recover {
      case x => {
        log.ex(x, "remote graph agg error: " + remote.id)
        periods.map(p => SMGImage.errorImage(aobj, p, Some(remote.id)))
      }
    }
  }

  private def goptsMap(gopts:GraphOptions) = {
    val ret = mutable.Map[String, Seq[String]]()
    if (gopts.disablePop) ret("dpp") = Seq("on")
    if (gopts.disable95pRule) ret("d95p") = Seq("on")
    if (gopts.step.isDefined) ret("step") = Seq(gopts.step.get.toString)
    if (gopts.pl.isDefined) ret("pl") = Seq(gopts.pl.get)
    if (gopts.maxY.isDefined) ret("maxy") = Seq(gopts.maxY.get.toString)
    ret.toMap
  }
  /**
    *  Asynchronous call to POST /api/reloadLocal to request from the remote instance to reload its configuration
    */
  def reloadConf(): Unit = {
    ws.url(remote.url + API_PREFIX + "reloadLocal").post("")
  }

  /**
    * TODO
    *
    * @param oid
    * @param params
    * @return
    */
  def fetchRows(oid: String, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]] = {
    ws.url(remote.url + API_PREFIX + "fetch/" + oid + "?" + params.fetchUrlParams ).
      withRequestTimeout(graphTimeoutMs).get().map { resp =>
      val jsval = Json.parse(resp.body)
      jsval.as[Seq[SMGRrdRow]]
    }.recover {
      case x: Throwable => {
        log.ex(x, s"fetchRows exception: $oid params=$params")
        Seq()
      }
    }
  }

  /**
    * TODO
    *
    * @param oids
    * @param op
    * @param params
    * @return
    */
  def fetchAggRows(oids: List[String], op: String, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]] = {
    var fup = params.fetchUrlParams
    if (fup != "") fup = "&" + fup
    ws.url(remote.url + API_PREFIX + "fetchAgg?ids=" + oids.mkString(",") + "&op=" + op + fup).
      withRequestTimeout(graphTimeoutMs).get().map { resp =>
      val jsval = Json.parse(resp.body)
      jsval.as[Seq[SMGRrdRow]]
    }.recover {
      case x: Throwable => {
        log.ex(x, s"fetchAggRows exception: $oids $op params=$params")
        Seq()
      }
    }
  }

  /**
    * Download a remote object rrd file to a local file
    *
    * @param oid - object id (without remote prefix)
    * @param localFn - local filename to download to
    * @return
    */
  def downloadRrd(oid: String, localFn: String): Future[Boolean] = {
      val downloadFn = localFn + ".tmp-" + Thread.currentThread().getId
      downloadUrl(remote.url + API_PREFIX + "rrd/" + oid, downloadFn).map { ret =>
        try {
          if (ret) {
            Files.move(new File(downloadFn).toPath, new File(localFn).toPath,
              StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
          }
          ret
        } catch {
          case x: Throwable => {
            log.ex(x, s"downloadRrd rename error: $downloadFn $localFn")
            false
          }
        }
      }
  }

  private def downloadUrl(url: String, outFn: String): Future[Boolean] = {
    //new URL(url) #> new File(outFn) !!
    ws.url(url).withRequestTimeout(graphTimeoutMs).get().map { resp =>
      try {
        Files.write(new File(outFn).toPath, resp.bodyAsBytes)
        true
      } catch {
        case x: Throwable => {
          log.ex(x, s"downloadUrl write error: $outFn $url")
          false
        }
      }
    }.recover {
      case x => {
        log.ex(x, "downloadUrl download error: " + url)
        false
      }
    }
  }

  def pluginData(pluginId: String, httpParams: Map[String, String]): Future[String] = {
    ws.url(remote.url + API_PREFIX + "plugin/" + pluginId).withQueryString(httpParams.toList:_*).
      get().map(_.body).recover{ case e: Exception => "" }
  }

  def monitorLogs(periodStr: String, limit: Int, hardOnly: Boolean): Future[Seq[SMGMonitorLogMsg]] = {
    val softStr = if (hardOnly) "" else "&soft=on"
    lazy val errRet = Seq(
      SMGMonitorLogMsg(SMGMonitorLogMsg.SMGERR,
        (System.currentTimeMillis() / 1000).toInt,
        "Remote logs fetch error", 1, isHard = true, Seq(), None, remote))
    ws.url(remote.url + API_PREFIX + "monitor/log?p=" + SMGRrd.safePeriod(periodStr) + "&l=" + limit + softStr).
      withRequestTimeout(shortTimeoutMs).get().map { resp =>
      Try {
        val jsval = Json.parse(resp.body)
        jsval.as[Seq[SMGMonitorLogMsg]]
      }.recover {
        case x => {
          log.ex(x, "remote monitor/log parse error: " + remote.id)
          errRet
        }
      }.get
    }.recover {
      case x => {
        log.ex(x, "remote monitor/log fetch error: " + remote.id)
        errRet
      }
    }
  }

  def monitorIssues(includeSoft: Boolean, includeAcked: Boolean, includeSilenced: Boolean): Future[Seq[SMGMonState]] = {
    val params = ListBuffer[String]()
    if (includeSoft) params += "soft=on"
    if (includeAcked) params += "ack=on"
    if (includeSilenced) params += "slc=on"
    val paramsStr = if (params.isEmpty) "" else "?" + params.mkString("&")
    lazy val errRet = Seq(SMGMonStateGlobal("Remote data unavailable", remote.id,
      SMGState((System.currentTimeMillis() / 1000).toInt, SMGState.E_SMGERR, "data unavailable")))
    ws.url(remote.url + API_PREFIX + "monitor/issues" + paramsStr).withRequestTimeout(shortTimeoutMs).get().map { resp =>
      Try {
        val jsval = Json.parse(resp.body)
        jsval.as[Seq[SMGMonStateView]]
      }.recover {
        case x => {
          log.ex(x, "remote monitor/state parse error: " + remote.id)
          errRet
        }
      }.get
    }.recover {
      case x => {
        log.ex(x, "remote monitor/state fetch error: " + remote.id)
        errRet
      }
    }
  }

  def monitorSilence(oid: String, act: SMGMonSilenceAction): Future[Boolean] = {
//    oid: String, act: String, slnc: Option[String], unt: Option[Int]
    val actStr = s"act=${act.action.toString}"
    val slncStr = if (act.silence) "&slnc=on" else "&slnc=off"
    val untStr = if (act.until.isDefined) s"&unt=${act.until.get}" else ""
    ws.url(remote.url + API_PREFIX + "monitor/silence/" + toLocalId(oid) + "?" + actStr + slncStr + untStr).
      withRequestTimeout(shortTimeoutMs).get().map(_ => true).recover { case _ => false }
  }

  def heatmap(flt: SMGFilter, maxSize: Option[Int], offset: Option[Int], limit: Option[Int]): Future[SMGMonHeatmap] = {
    lazy val errRet = SMGMonHeatmap(List(SMGMonStateGlobal("Remote data unavailable", remote.id,
      SMGState((System.currentTimeMillis() / 1000).toInt, SMGState.E_SMGERR, "data unavailable"))), 1)
    val urlParams = flt.asUrl +
      maxSize.map(v => s"&maxSize=$v").getOrElse("") +
      offset.map(v => s"&offset=$v").getOrElse("") +
      limit.map(v => s"&limit=$v").getOrElse("")
    ws.url(remote.url + API_PREFIX + "monitor/heatmap?" + urlParams).
      withRequestTimeout(graphTimeoutMs).get().map { resp =>
      Try {
        val jsval = Json.parse(resp.body)
        jsval.as[SMGMonHeatmap]
      }.recover {
        case x => {
          log.ex(x, "remote monitor/heatmap parse error: " + remote.id)
          errRet
        }
      }.get
    }.recover {
      case x => {
        log.ex(x, "remote monitor/heatmap fetch error: " + remote.id)
        errRet
      }
    }
  }

  def objectViewsStates(ovs: Seq[SMGObjectView]): Future[Map[String,Seq[SMGMonStateObjVar]]] = {
    val oids: String = ovs.map(o => toLocalId(o.id)).mkString(",")
    val postMap = Map("ids" -> Seq(oids))
    ws.url(remote.url + API_PREFIX + "monitor/ovstates").
      withRequestTimeout(shortTimeoutMs).post(postMap).map { resp =>
      Try {
        val jsval = Json.parse(resp.body)
        jsval.as[Map[String,Seq[SMGMonStateObjVar]]].map(t => (prefixedId(t._1), t._2))
      }.recover {
        case x => {
          log.ex(x, "remote monitor/ovstates parse error: " + remote.id)
          Map[String,Seq[SMGMonStateObjVar]]()
        }
      }.get
    }.recover {
      case x => {
        log.ex(x, "remote monitor/ovstates fetch error: " + remote.id)
        Map[String,Seq[SMGMonStateObjVar]]()
      }
    }
  }
}

/**
  * Singleton defining object serializers (writes), used by Api controller
  */
object SMGRemoteClient {


  // A bit of a hack to avoid code duplication
  def writeNonAggObject(obj: SMGObjectView) = Json.obj(
    "id" -> obj.id,
    "interval" -> obj.interval,
    "vars" -> Json.toJson(obj.vars),
    "cdefVars" -> Json.toJson(obj.cdefVars),
    "graphVarsIndexes" -> Json.toJson(obj.graphVarsIndexes),
    "title" -> obj.title,
    "stack" -> obj.stack
  )

  def writeAggObject(obj: SMGAggObjectView) = {
    implicit val naow =  new Writes[SMGObjectView] {
      def writes(obj: SMGObjectView) = {
        writeNonAggObject(obj)
      }
    }
    Json.obj(
      "id" -> obj.id,
      "objs" -> Json.toJson(obj.objs),
      "op" -> obj.op,
      "vars" -> Json.toJson(obj.vars),
      "cdefVars" -> Json.toJson(obj.cdefVars),
      "graphVarsIndexes" -> Json.toJson(obj.graphVarsIndexes),
      "title" -> obj.title,
      "stack" -> obj.stack,
      "is_agg" -> true
    )
  }

  implicit val smgObjectViewWrites = new Writes[SMGObjectView] {
    def writes(obj: SMGObjectView) = {
      if (obj.isAgg) {
        writeAggObject(obj.asInstanceOf[SMGAggObjectView])
      } else {
        writeNonAggObject(obj)
      }
    }
  }

  implicit val smgGraphOptionsWrites = new Writes[GraphOptions] {

    def writes(gopts: GraphOptions) = {
      val mm = mutable.Map[String,JsValue]()
      if (gopts.step.isDefined) mm += ("step" -> Json.toJson(gopts.step.get))
      if (gopts.pl.isDefined) mm += ("pl" -> Json.toJson(gopts.pl.get))
      if (gopts.xsort.isDefined) mm += ("xsort" -> Json.toJson(gopts.xsort.get))
      if (gopts.disablePop) mm += ("dpp" -> Json.toJson("true"))
      if (gopts.disable95pRule) mm += ("d95p" -> Json.toJson("true"))
      if (gopts.maxY.isDefined) mm += ("maxy" -> Json.toJson(gopts.maxY.get))
      Json.toJson(mm.toMap)
    }
  }

  implicit val smgFilterWrites = new Writes[SMGFilter] {

    def writes(flt: SMGFilter) = {
      val mm = mutable.Map[String,JsValue]()
      if (flt.px.isDefined) mm += ("px" -> Json.toJson(flt.px.get))
      if (flt.sx.isDefined) mm += ("sx" -> Json.toJson(flt.sx.get))
      if (flt.rx.isDefined) mm += ("rx" -> Json.toJson(flt.rx.get))
      if (flt.rxx.isDefined) mm += ("rxx" -> Json.toJson(flt.rxx.get))
      if (flt.trx.isDefined) mm += ("trx" -> Json.toJson(flt.trx.get))
      if (flt.remote.isDefined) mm += ("remote" -> Json.toJson(flt.remote.get))
      if (flt.gopts != GraphOptions()) mm += ("gopts" -> Json.toJson(flt.gopts))
      Json.toJson(mm.toMap)
    }
  }

  implicit val smgConfIndexWrites = new Writes[SMGConfIndex] {
    def writes(ix: SMGConfIndex) = {
      val mm = mutable.Map(
        "id" -> Json.toJson(ix.id),
        "title" -> Json.toJson(ix.title),
        "flt" -> Json.toJson(ix.flt)
      )
      if (ix.cols.isDefined) mm += ("cols" -> Json.toJson(ix.cols.get))
      if (ix.rows.isDefined) mm += ("rows" -> Json.toJson(ix.rows.get))
      if (ix.aggOp.isDefined) mm += ("agg_op" -> Json.toJson(ix.aggOp.get))
      if (ix.xAgg) mm += ("xagg" -> Json.toJson("true"))
      if (ix.period.isDefined) mm += ("period" -> Json.toJson(ix.period.get))
      if (ix.desc.isDefined) mm += ("desc" -> Json.toJson(ix.desc.get))
      if (ix.parentId.isDefined) mm += ("parent" -> Json.toJson(ix.parentId.get))
      if (ix.childIds.nonEmpty) mm += ("children" -> Json.toJson(ix.childIds))
      if (ix.disableHeatmap) mm += ("dhmap" -> Json.toJson("true"))
      Json.toJson(mm.toMap)
    }
  }

  implicit val smgConfigWrites = new Writes[SMGLocalConfig] {
    def writes(conf: SMGLocalConfig) = Json.obj(
      "globals" -> conf.globals,
      "objects" -> Json.toJson(conf.viewObjects),
      "indexes" -> Json.toJson(conf.indexes),
      "urlPrefix" -> conf.urlPrefix
    )
  }

  implicit val smgImageWrites = new Writes[SMGImageView] {
    def writes(img: SMGImageView) = Json.obj(
      "imageUrl" -> img.imageUrl,
      "obj" -> Json.toJson(img.obj),
      "period" -> img.period
    )
  }

  implicit val smgAggImageWrites = new Writes[SMGAggImage] {
    def writes(img: SMGAggImage) = Json.obj(
      "imageUrl" -> img.imageUrl,
      "obj" -> Json.toJson(img.obj),
      "period" -> img.period
    )
  }

  implicit val smgRrdRowWrites = new Writes[SMGRrdRow] {
    def writes(row: SMGRrdRow) = {
      val valOpts = row.vals.map(f => if (f.isNaN) None else Some(f))
      Json.obj(
        "tss" -> row.tss,
        "vals" -> Json.toJson(valOpts)
      )
    }
  }

  implicit val smgStateWrites = new Writes[SMGState] {
    def writes(ms: SMGState) = {
      //SMGState(ts: Int, state: SMGState.Value, desc: String)
      Json.obj(
        "ts" -> ms.ts,
        "state" -> ms.state.toString,
        "desc" -> ms.desc
      )
    }
  }

  implicit val smgMonStateObjVarWrites = new Writes[SMGMonStateObjVar] {
    def writes(ms: SMGMonStateObjVar) = {
      val mm = mutable.Map(
        "ouid" -> Json.toJson(ms.ouid),
        "ix" -> Json.toJson(ms.ix),
        "ovids" -> Json.toJson(ms.ovids),
        "title" -> Json.toJson(ms.title),
        "label" -> Json.toJson(ms.label),
        "rs" -> Json.toJson(ms.recentStates)
      )
      if (ms.pfId.isDefined) mm += ("pfid" -> Json.toJson(ms.pfId.get))
      if (ms.isAcked) mm += ("ack" -> Json.toJson(1))
      if (ms.isSilenced) mm += ("slc" -> Json.toJson(1))
      if (ms.silencedUntil.isDefined) mm += ("sunt" -> Json.toJson(ms.silencedUntil.get))
      if (ms.badSince.isDefined) mm += ("bs" -> Json.toJson(ms.badSince.get))
      Json.toJson(mm.toMap)
    }
  }

  implicit val smgMonStateObjListWrites = new Writes[Seq[SMGMonStateObjVar]] {
    def writes(mss: Seq[SMGMonStateObjVar]) = {
      Json.toJson(mss)
    }
  }

  implicit val smgMonStateWrites = new Writes[SMGMonState] {
    def writes(ms: SMGMonState) = {
      val mm = mutable.Map(
        "s" -> Json.toJson(ms.severity),
        "t" -> Json.toJson(ms.text),
        "h" -> Json.toJson(if (ms.isHard) 1 else 0),
        "v" -> Json.toJson(ms.currentStateVal),
        "er" -> Json.toJson(ms.errorRepeat)
      )
      if (ms.isAcked) mm += ("a" -> Json.toJson(1))
      if (ms.isSilenced) mm += ("sl" -> Json.toJson(1))
      if (ms.silencedUntil.isDefined) mm += ("su" -> Json.toJson(ms.silencedUntil.get))
      if (ms.oid.isDefined) mm += ("o" -> Json.toJson(ms.oid.get))
      if (ms.pfId.isDefined) mm += ("pf" -> Json.toJson(ms.pfId.get))
      if (ms.aggShowUrlFilter.isDefined) mm += ("uf" -> Json.toJson(ms.aggShowUrlFilter.get))
      if (ms.badSince.isDefined) mm += ("bs" -> Json.toJson(ms.badSince.get))
      Json.toJson(mm.toMap)
    }
  }

  implicit val smgMonHeatmapWrites = new Writes[SMGMonHeatmap] {
    def writes(mh: SMGMonHeatmap) = {
      //  case class SMGMonHeatmap(lst: List[SMGMonState], statesPerSquare: Int)
      Json.obj(
        "lst" -> mh.lst,
        "sps" -> mh.statesPerSquare
      )
    }
  }

  implicit val smgMonitorLogWrites = new Writes[SMGMonitorLogMsg] {
    def writes(ml: SMGMonitorLogMsg) = {
      //  SMGMonitorLogMsg(mltype: SMGMonitorLogMsg.Value,
      //    ts: Int,
      //    msg: String,
      //    repeat: Int,
      //    isHard: Boolean,
      //    ouid: Option[String],
      //    vix: Option[Int])
      val mm = mutable.Map(
        "mlt" -> Json.toJson(ml.mltype.toString),
        "ts" -> Json.toJson(ml.ts),
        "msg" -> Json.toJson(ml.msg),
        "rpt" -> Json.toJson(ml.repeat)
      )
      if (ml.isHard) mm += ("hard" -> Json.toJson("true"))
      if (ml.ouids.nonEmpty) mm += ("ouids" -> Json.toJson(ml.ouids))
      if (ml.vix.isDefined)  mm += ("vix" -> Json.toJson(ml.vix.get))
      Json.toJson(mm.toMap)
    }
  }

}

