package controllers

import java.io.InputStream
import java.util.Date
import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import com.ning.http.client.providers.netty.response.NettyResponse
import com.smule.smg._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.ws.WSClient
import play.api.mvc._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import play.api.mvc.Cookie
import play.api.mvc.DiscardingCookie


@Singleton
class Application  @Inject() (actorSystem: ActorSystem,
                              smg: GrapherApi,
                              configSvc: SMGConfigService,
                              scheduler: SMGSchedulerApi,
                              remotes: SMGRemotesApi,
                              monitorApi: SMGMonitorApi,
                              ws: WSClient)  extends Controller {

  val log = SMGLogger


  val SMG_MONITOR_STATE_COOKIE_NAME = "smg-monitor-state"

  val MAX_INDEX_LEVELS = 5


  private def parseRemoteParam(remote: String): Tuple2[List[String],Option[String]] = {
    val remoteIds = SMGRemote.local.id :: configSvc.config.remotes.map(_.id).toList
    val rmtOpt = remote match {
      case "*" => None
      case rmtId => remoteIds.find(_ == rmtId)
    }
    val availRemotes = if (configSvc.config.remotes.isEmpty) List() else "*" :: remoteIds
    (availRemotes , rmtOpt)
  }

  /**
    * List all topl-level configured indexes
    */
  def index(remote :String, lvls: Option[Int]) = Action { request =>
    val (availRemotes, rmtOpt) = parseRemoteParam(remote)
    val tlIndexesByRemote = smg.getTopLevelIndexesByRemote(rmtOpt)
    val myLevels = lvls.getOrElse(configSvc.config.indexTreeLevels)
    val saneLevels = scala.math.min(scala.math.max(1, myLevels), MAX_INDEX_LEVELS)
    Ok(views.html.index(rmtOpt, availRemotes, saneLevels, configSvc.config.indexTreeLevels,
      tlIndexesByRemote, smg.detailPeriods.drop(1), configSvc.plugins))
  }

  private def msEnabled(request: Request[AnyContent]) = request.cookies.get(SMG_MONITOR_STATE_COOKIE_NAME).map(_.value).getOrElse("on") == "on"

  /**
    * List all automatically discovered indexes in a tree-like display
    */
  def autoindex(root: String, expandLevels: Int) = Action {
    val topLevel = smg.getAutoIndex
    val dispIx = topLevel.findChildIdx(root)
    if (dispIx.isEmpty) {
      Ok("Index id not found: " + root)
    } else {
      Ok(views.html.autoindex(dispIx.get, smg.detailPeriods, expandLevels, configSvc.plugins))
    }
  }


  private def xsortObjectViews(lst: Seq[SMGObjectView], sortBy: Int, period: String): Seq[SMGObjectView] = {
    // convert the seq of objects to a seq of (object,recent_values) tuples by doing (async) fetches
    val futs = lst.map { ov =>
      smg.fetch(ov, SMGRrdFetchParams(None, Some(period), None, filterNan = true )).map { rseq =>
        (ov, rseq)
      }
    }
    val results = futs.map { fut =>
      Await.result(fut,  Duration(120, "seconds"))
    }
    // first group by vars trying to preserve ordering
    val byGraphVars = mutable.Map[List[Map[String,String]],ListBuffer[(SMGObjectView, Seq[SMGRrdRow])]]()
    val orderedVars = ListBuffer[List[Map[String,String]]]()
    results.foreach { t =>
      val curVars = t._1.filteredVars(true)
      if (!byGraphVars.contains(curVars)){
        byGraphVars(curVars) = ListBuffer[(SMGObjectView, Seq[SMGRrdRow])]()
        orderedVars += curVars
      }
      byGraphVars(curVars) += t
    }
    // sort by the average value of the var with sortBy index within each group of vars
    orderedVars.flatMap { v =>
      byGraphVars(v).toList.sortBy {t =>
        if (t._2.isEmpty)
          0.0
        else {
          // average the recent values
          val numSeq = t._2.filter(sortBy < _.vals.size).map(_.vals(sortBy))
          if (numSeq.isEmpty)
            0.0
          else
            - numSeq.foldLeft(0.0) {(x,y) => x + y} / numSeq.size
        }
      }.map(_._1)
    }
  }

  private def optStr2OptDouble(opt: Option[String]): Option[Double] = if (opt.isDefined && (opt.get != "")) Some(opt.get.toDouble) else None


  /**
    * Display a set of images based on serach filter and display params
    *
    * @param ix - optional index id to be used (matching to that index id filter will be used)
    * @param px - optional filter prefix
    * @param sx - optional filter suffix
    * @param rx - optional filter regex
    * @param rxx - optional filter regex to NOT match
    * @param remote - optional filter remote
    * @param agg - optional aggregate function (can be STACK, SUM etc)
    * @param period - graphs period
    * @param cols - optional number of columns in which to display graphs
    * @param rows - optional max number of rows in which to display graphs. Excess graphs are paginated
    * @param pg - optional page number to display (if results are paginated)
    * @param xagg - optional flag whether to aggregate cross-colos
    * @param xsort - integer indicating whether to sort by the descending value of variable with that index. 0 means no sorting
    * @return
    */
  def dashboard(
                 ix: Option[String],
                 px: Option[String],
                 sx: Option[String],
                 rx: Option[String],
                 rxx: Option[String],
                 trx: Option[String],
                 remote: Option[String],
                 agg: Option[String],
                 period: String,
                 pl: Option[String],
                 step: Option[String],
                 cols: Int,
                 rows: Int,
                 pg: Int,
                 xagg: String,
                 xsort: Int,
                 dpp: String,
                 d95p: String,
                 maxy: Option[String]
               ) = Action.async { request =>
    // TODO replace the long param list above with SMGFilter.fromParams(request.queryString)
    val showMs = msEnabled(request)
    val idx = if (ix.nonEmpty) {
      smg.getIndexById(ix.get)
    } else None
    val parentIdx: Option[SMGIndex] = if (idx.isDefined && idx.get.parentId.isDefined) {
      smg.getIndexById(idx.get.parentId.get)
    } else None

    // use index filter/aggOp/xAgg if url specifies empty filter
    // form is overriding index spec
    val myXSort = if (idx.isEmpty || (xsort > 0)) xsort else idx.get.flt.gopts.xsort.getOrElse(0)
    val istep = if (step.getOrElse("") != "") SMGRrd.parseStep(step.get) else None
    val myStep = if (idx.isEmpty || istep.isDefined) istep else idx.get.flt.gopts.step
    val myDisablePop = if (idx.isEmpty || (dpp == "on")) dpp == "on" else idx.get.flt.gopts.disablePop
    val myDisable95p = if (idx.isEmpty || (d95p == "on")) d95p == "on" else idx.get.flt.gopts.disable95pRule
    val myMaxY = if (idx.isEmpty || maxy.isDefined) optStr2OptDouble(maxy) else idx.get.flt.gopts.maxY
    val gopts = GraphOptions(step = myStep, pl = pl, xsort = Some(myXSort),
      disablePop = myDisablePop, disable95pRule = myDisable95p, maxY = myMaxY)
    val myAgg = if (idx.isEmpty || agg.isDefined) agg else idx.get.aggOp
    var xRemoteAgg = if (idx.isEmpty || (xagg == "on")) xagg == "on" else idx.get.xAgg

    val flt = if (px.isEmpty && sx.isEmpty && rx.isEmpty && rxx.isEmpty && trx.isEmpty && idx.nonEmpty) {
      idx.get.flt
    }
    else
      SMGFilter(px, sx, rx, rxx, trx, remote, gopts)

    val conf = configSvc.config
    val availRemotes = if (conf.remotes.nonEmpty)
      Seq(SMGRemote.local) ++ conf.remotes ++ Seq(SMGRemote.wildcard)
    else
      Seq[SMGRemote]()

    // filter results and slice acording to pagination
    var maxPages = 1
    var tlObjects = 0
    val limit = cols * rows
    val objsSlice = if (limit <= 0) {
      Seq()
    } else {
      val offset = pg * limit
      val filteredObjects = smg.getFilteredObjects(flt)
      tlObjects = filteredObjects.size
      maxPages = (tlObjects / limit) + (if ((tlObjects % limit) == 0) 0 else 1)
      val sliced = filteredObjects.slice(offset, offset + limit)
      if (gopts.xsort.getOrElse(0) > 0)
        xsortObjectViews(sliced, gopts.xsort.get - 1, period)
      else
        sliced
    }

    // get two futures - one for the images we want and the other for the respective monitorStates
    val (futImages, futMonitorStates) = if (objsSlice.isEmpty) {
      ( Future { Seq() },
        Future { Map() } )
    } else if (myAgg.nonEmpty) {
      // group objects by "graph vars" (identical var defs, subject to aggregation) and produce an aggregate
      // object and corresponding image for each group
      val byGraphVars = mutable.Map[List[Map[String,String]],ListBuffer[SMGObjectView]]()
      val orderedVars = ListBuffer[List[Map[String,String]]]()
      objsSlice.foreach { ov =>
        val curVars = ov.filteredVars(true)
        if (!byGraphVars.contains(curVars)){
          byGraphVars(curVars) = ListBuffer[SMGObjectView]()
          orderedVars += curVars
        }
        byGraphVars(curVars) += ov
      }
      val aggObjs = for (v <- orderedVars.toList) yield SMGAggObject.build(byGraphVars(v).toList, myAgg.get)
      val aggFutureSeqs = for (ao <- aggObjs) yield smg.graphAggObject(ao, Seq(period), gopts, xRemoteAgg)
      (Future.sequence(aggFutureSeqs).map { sofs => sofs.flatten },
        if (showMs) monitorApi.objectViewStates(aggObjs) else Future { Map() } )
    } else {
      ( smg.graphObjects(objsSlice, Seq(period), gopts),
        if (showMs) monitorApi.objectViewStates(objsSlice) else Future { Map() } )
    }

    // We need both the (future) images and monitor states resolved before responding
    Future.sequence(Seq(futImages, futMonitorStates)).map { mySeq =>
      val lst = mySeq(0).asInstanceOf[List[SMGImageView]]
      val monStatesByImgView = mySeq(1).asInstanceOf[Map[String,Seq[SMGMonState]]]
      //preserve order
      val monOverview = lst.flatMap(iv => monStatesByImgView.getOrElse(iv.obj.id, List()))
      // make sure we find agg objects and their op even if not specified in url params but e.g. coming from plugin
      val myAggOp = if (myAgg.isDefined) myAgg else lst.find(_.obj.isAgg).map(_.obj.asInstanceOf[SMGAggObjectView].op)
      val byRemoteMap = lst.groupBy( img => img.remoteId.getOrElse("") )
      val byRemote = (List("") ++ remotes.configs.map(rc => rc.remote.id)).
        filter(rid => byRemoteMap.contains(rid)).map( rid => (rid, byRemoteMap(rid)) )
      Ok(
        views.html.filterResult(configSvc.plugins, idx, parentIdx, byRemote, flt, period, cols, rows, pg, maxPages,
          lst.size, objsSlice.size, tlObjects, availRemotes, myAggOp, xRemoteAgg,
          configSvc.config.rrdConf.imageCellWidth, gopts, showMs, monStatesByImgView, monOverview)
      )
    }
  }

  /**
    * Display graphs for a single object based on id in all default periods
    *
    * @param oid - object id
    * @param cols - number fo columns to display graphs in
    * @return
    */
  def show(oid:String, cols: Int, dpp: String, d95p: String, maxy: Option[String]) = Action.async { request =>
    val showMs = msEnabled(request)
    val gopts = GraphOptions(step = None, pl = None, xsort = None,
      disablePop = dpp == "on", disable95pRule = d95p == "on", maxY = optStr2OptDouble(maxy))
    smg.getObjectView(oid) match {
      case Some(obj) => {
        val gfut = smg.getObjectDetailGraphs(obj, gopts)
        val mfut =  if (showMs) monitorApi.objectViewStates(Seq(obj)) else Future { Map() }
        Future.sequence(Seq(gfut,mfut)).map { t =>
          val lst = t(0).asInstanceOf[Seq[SMGImageView]]
          val ms = t(1).asInstanceOf[Map[String,Seq[SMGMonState]]].flatMap(_._2).toList
          Ok(views.html.show(configSvc.plugins, obj, lst, cols, configSvc.config.rrdConf.imageCellWidth, gopts, showMs, ms))
        }
      }
      case None => Future { }.map { _ => NotFound("object id not found") }
    }
  }

  /**
    * Display aggregated graphs as a single object in all default periods
    *
    * @param ids - list fo comma separated object ids
    * @param op - aggregation function
    * @param title - optional title to use
    * @param cols - number fo columns to display graphs in
    * @return
    */
  def showAgg(ids:String, op:String, title:Option[String], cols: Int, dpp: String, d95p: String, maxy: Option[String]) = Action.async { request =>
    val showMs = msEnabled(request)
    val gopts = GraphOptions(step = None, pl = None, xsort = None,
      disablePop = dpp == "on", disable95pRule = d95p == "on", maxY = optStr2OptDouble(maxy))
    val idLst = ids.split(',')
    val objList = idLst.filter( id => smg.getObjectView(id).nonEmpty ).map(id => smg.getObjectView(id).get)
    if (objList.isEmpty)
      Future { }.map { _ => NotFound("object ids not found") }
    else {
      val byRemote = objList.groupBy(o => SMGRemote.remoteId(o.id))
      val aobj = SMGAggObject.build(objList, op, title)
      val gfut = smg.graphAggObject(aobj, smg.detailPeriods, gopts, byRemote.keys.size > 1)
      val mfut = if (showMs) monitorApi.objectViewStates(objList) else Future { Map() }
      Future.sequence(Seq(gfut,mfut)).map { t =>
        val lst = t(0).asInstanceOf[Seq[SMGImageView]]
        val ms = t(1).asInstanceOf[Map[String,Seq[SMGMonState]]].flatMap(_._2).toList
        Ok(views.html.show(configSvc.plugins, aobj, lst, cols, configSvc.config.rrdConf.imageCellWidth, gopts, showMs, ms))
      }
    }
  }


  /**
    * Fetch data (rows) for given object id and render as csv
    *
    * @param oid
    * @param r
    * @param s
    * @param e
    * @param d
    * @return
    */
  def fetch(oid: String, r:Option[Int], s: Option[String], e: Option[String], d:Boolean) = Action.async {
    val obj = smg.getObjectView(oid)
    if (obj.isEmpty) Future { Ok("Object Id Not Found") }
    else {
      val params = SMGRrdFetchParams(r, s, e, filterNan = false)
      smg.fetch(obj.get, params).map { ret =>
        fetchCommon(obj.get, d, ret)
      }
    }
  }

  /**
    * Fetch data (rows) for given agregate object (list of ids + an op) and render as csv
    *
    * @param ids
    * @param op
    * @param r - resolution (step)
    * @param s - start
    * @param e - end
    * @param d - download or inline display
    * @return
    */
  def fetchAgg(ids:String, op:String, r:Option[Int], s: Option[String], e: Option[String], d:Boolean) = Action.async {
    val idLst = ids.split(',')
    val objList = idLst.filter( id => smg.getObjectView(id).nonEmpty ).map(id => smg.getObjectView(id).get)
    if (objList.isEmpty)
      Future { }.map { _ => NotFound("object ids not found") }
    else {
      val aobj = SMGAggObject.build(objList, op)
      val params = SMGRrdFetchParams(r, s, e, filterNan = false)
      smg.fetchAgg(aobj, params).map { ret =>
        fetchCommon(aobj, d, ret)
      }
    }
  }

  def fetchCommon(ov: SMGObjectView, d: Boolean, ret: Seq[SMGRrdRow]): Result = {
    val httpHdrs = mutable.Map[String,String]()
    val hdr = if (ret.isEmpty) "Object Data Not Found\n" else {
      if (d){
        httpHdrs(CONTENT_DISPOSITION) = s"attachment; filename=" + ov.id + ".csv"
        httpHdrs(CONTENT_TYPE) = "text/csv"
      }

      def ovars(ov: SMGObjectView) = if (ov.cdefVars.nonEmpty)
        ov.cdefVars
      else
        ov.filteredVars(false)


      val vlst = if (ov.isAgg) {
        val aov = ov.asInstanceOf[SMGAggObjectView]
        if ((aov.op == "GROUP") || (aov.op == "STACK") ) {
          val shortIds = SMGAggObject.stripCommonStuff('.', aov.objs.map(o => o.id)).iterator
          aov.objs.flatMap{ o =>
            val sid = shortIds.next()
            ovars(o).map { v => // override labels
              v ++ Map("label" -> (sid + "-" + v.getOrElse("label", "dsX")))
            }
          }
        } else ovars(ov)
      } else ovars(ov)

      "unixts,date," + vlst.map(_.getOrElse("label", "dsX")).mkString(",") +"\n"
    }
    Ok(
      hdr + ret.map { row =>
        (Seq(row.tss.toString, new Date(row.tss.toLong * 1000).toString) ++ row.vals.map(_.toString)).mkString(",")
      }.mkString("\n")
    ).withHeaders(httpHdrs.toList:_*)
  }

  /**
    * Trigger an update job using external scheduler (e.g. cron). Do not use if using internal scheduler
    *
    * @param interval - interval for which to run the update job
    * @return
    */
  def runJob(interval: Int) = Action {
    smg.run(interval)
    Ok("OK")
  }

  /**
    * Reload local config from disk and propagate the reload command to all configured remotes.
    *
    * @return
    */
  def reloadConf = Action {
    configSvc.reload
    //TODO XXX up to now SMG would notify all (slave) remotes, now - only masters
    remotes.notifyMasters()
    if (configSvc.config.reloadSlaveRemotes) {
      remotes.notifySlaves()
    }
    remotes.fetchConfigs()
    Ok("OK")
  }

  def pluginIndex(pluginId: String) = Action { request =>
    val httpParams = request.queryString.map { case (k,v) => k -> v.mkString }
    configSvc.plugins.find( p => p.pluginId == pluginId) match {
      case Some(plugin) => {
        Ok(views.html.pluginIndex(plugin, plugin.htmlContent(httpParams), configSvc.plugins))
      }
      case None => Ok("Plugin not found - " + pluginId)
    }
  }

  val DEFAULT_INDEX_HEATMAP_MAX_SIZE = 300

  def monitorIndexSvg(ixid: String) = Action.async {
    val ixObj = smg.getIndexById(ixid)
    if (ixObj.isEmpty)
      Future {  Ok(views.html.monitorSvgNotFound()).as("image/svg+xml") }
    else if (ixObj.get.disableHeatmap || ixObj.get.flt.matchesAnyObjectIdAndText) {
      Future {  Ok(views.html.monitorSvgDisabled()).as("image/svg+xml") }
    } else {
      val tpl = (ixid, smg.getFilteredObjects(ixObj.get.flt))
      monitorApi.heatmap(ixObj.get.flt, Some(DEFAULT_INDEX_HEATMAP_MAX_SIZE), None, None).map { hms =>
        val hmLst = hms.map(_._2)
        val combinedHm = SMGMonHeatmap.join(hmLst)
        Ok(views.html.monitorSvgHeatmap(combinedHm)).as("image/svg+xml")
      }
    }
  }

  def monitorIssues(soft: Option[String], ackd: Option[String], slncd: Option[String], action: Option[String], oid:Option[String], until: Option[String]) = Action.async {
    action match {
      case Some(act) => {
        val fut = if (oid.isDefined) {
          val (actv, slnc) = act match {
            case "ack" => (SMGMonSilenceAction.ACK, true)
            case "unack" => (SMGMonSilenceAction.ACK, false)
            case "slnc" => (SMGMonSilenceAction.SILENCE, true)
            case "unslnc" => (SMGMonSilenceAction.SILENCE, false)
            case "ackpf" => (SMGMonSilenceAction.ACK_PF, true)
            case "unackpf" => (SMGMonSilenceAction.ACK_PF, false)
            case "slncpf" => (SMGMonSilenceAction.SILENCE_PF, true)
            case "unslncpf" => (SMGMonSilenceAction.SILENCE_PF, false)
            case s: String => {
              log.error("Bad action provided" + s)
              (SMGMonSilenceAction.ACK, false)
            }
          }
          val untilTss = until.map(s => SMGState.tssNow + SMGRrd.parsePeriod(s).getOrElse(0))
          val slncAction = SMGMonSilenceAction(actv, slnc, untilTss)
          monitorApi.silenceObject(oid.get, slncAction)
        } else Future {
          log.error("monitorIssues: oid not provided")
          false
        }
        fut.map { b =>
          val rm = mutable.Map[String, Seq[String]]()
          if (soft.isDefined) rm += ("soft" -> Seq(soft.get))
          if (ackd.isDefined) rm += ("ackd" -> Seq(ackd.get))
          if (slncd.isDefined) rm += ("slncd" -> Seq(slncd.get))
          Redirect("/monitor", rm.toMap)
        }
      }
      case None => {
        val inclSoft = soft.getOrElse("off") == "on"
        val inclAck = ackd.getOrElse("off") == "on"
        val inclSlnc = slncd.getOrElse("off") == "on"
        monitorApi.problems(inclSoft, inclAck, inclSlnc).map { seq =>
          Ok(views.html.monitorIssues(configSvc.plugins, seq, inclSoft, inclAck, inclSlnc))
        }
      }
    }
  }

  val DEFAULT_HEATMAP_MAX_SIZE = 1800

  def monitorHeatmap = Action.async { request =>
    val params = request.queryString
    val flt = SMGFilter.matchAll //SMGFilter.fromParams(params)
    monitorApi.heatmap(flt,
      Some(params.get("maxSize").map(_.head.toInt).getOrElse(DEFAULT_HEATMAP_MAX_SIZE)),
      params.get("offset").map(_.head.toInt),
      params.get("limit").map(_.head.toInt)).map { data =>
      Ok(views.html.monitorHeatmap(configSvc.plugins, data))
    }
  }

  def monitorLog(remote: String, p: Option[String], l: Option[Int], ms: Option[String], soft: Option[String]) = Action.async {
    val (availRemotes, rmtOpt) = parseRemoteParam(remote)
    val minSev = ms.map{ s => SMGState.withName(s) }.getOrElse(SMGState.E_VAL_WARN)
    val period = p.getOrElse("24h")
    val limit = l.getOrElse(100)
    val hardOnly = soft.getOrElse("off") == "off"
    monitorApi.monLogApi.getSince(period, rmtOpt, limit, Some(minSev), hardOnly).map { logs =>
      Ok(views.html.monitorLog(configSvc.plugins, availRemotes, rmtOpt, SMGState.values.toList.map(_.toString),
        Some(minSev.toString), period, limit, hardOnly, logs))
    }
  }

  def userSettings() = Action { request =>
    var cookiesToSet = Seq[Cookie]()
    var cookiesToDiscard = Seq[DiscardingCookie]()
    var showMs = msEnabled(request)
    var msg: Option[String] = None
    if (request.method == "POST") {
      val params = request.body.asFormUrlEncoded.get
      showMs = params.getOrElse("ms", Seq("off")).head == "on"
      if (showMs) {
        cookiesToDiscard = Seq(DiscardingCookie(SMG_MONITOR_STATE_COOKIE_NAME))
      } else {
        cookiesToSet = Seq(Cookie(SMG_MONITOR_STATE_COOKIE_NAME, "off"))
      }
      msg = Some("Settings saved in your browser")
    }
    Ok(views.html.userSettings(configSvc.plugins, msg, showMs)).withCookies(cookiesToSet:_*).discardingCookies(cookiesToDiscard:_*)
  }

  def inspect(id: String) = Action {
    if (SMGRemote.isRemoteObj(id)) {
      // XXX fow now just redirect to the remore url
      val rid = SMGRemote.remoteId(id)
      val remote = configSvc.config.remotes.find(_.id == rid)
      if (remote.isDefined) {
        Redirect(s"/proxy/${remote.get.id}/inspect/" + SMGRemote.localId(id))
      } else NotFound("Remote object not found")
    } else {
      val ov = smg.getObjectView(id)
      if (ov.isDefined) {
        val ou = ov.get.refObj
        val ac = if (ou.isDefined) configSvc.config.objectAlertConfs.get(ou.get.id) else None
        val nc = if (ou.isDefined) configSvc.config.objectNotifyConfs.get(ou.get.id) else None
        val ncmds = SMGMonNotifySeverity.values.toList.map { v =>
          val cmdBackoff = configSvc.objectVarNotifyCmdsAndBackoff(ou.get, None, v)
          (v, cmdBackoff._1, cmdBackoff._2)
        }
        val hstate =  monitorApi.inspectObject(ov.get)
        Ok(views.html.inspectObject(ov.get, ac, nc, ncmds, hstate))
      } else NotFound("Object not found")
    }
  }

  val DEFAULT_SEARCH_RESULTS_LIMIT = 500

  def search(q: Option[String], lmt: Option[Int]) = Action.async {
    val maxRes = lmt.getOrElse(DEFAULT_SEARCH_RESULTS_LIMIT)
    smg.search(q.getOrElse(""), maxRes + 1).map { sres =>
      Ok(views.html.search(q = q.getOrElse(""),
        res = sres.take(maxRes),
        hasMore = sres.size == maxRes + 1,
        lmt = maxRes,
        defaultLmt = DEFAULT_SEARCH_RESULTS_LIMIT,
        plugins = configSvc.plugins))
    }
  }

  def monitorRunTree(remote: String, root: Option[String], lvls: Option[Int]) = Action.async { request =>
    val conf = configSvc.config
    val rootStr = root.getOrElse("")
    val treesMapFut = if (remote == "") {
      Future {
        conf.fetchCommandTreesWithRoot(root)
      }
    } else {
      remotes.monitorRunTree(remote, root)
    }
    val rootMonStateFut = if (rootStr == "") Future { None } else {
      monitorApi.fetchCommandState(rootStr)
    }
    Future.sequence(Seq(treesMapFut,rootMonStateFut)).map { seq =>
      val treesMap = seq(0).asInstanceOf[Map[Int,Seq[SMGFetchCommandTree]]]
      val rootMonState = seq(1).asInstanceOf[Option[SMGMonState]]
      val parentId = if (rootStr == "") None else {
        treesMap.values.flatten.find { t => t.node.id == rootStr }.flatMap(_.node.preFetch)
      }
      val maxLevels = if (rootStr != "") conf.MAX_RUNTREE_LEVELS else lvls.getOrElse(conf.runTreeLevelsDisplay)
      val remoteIds = SMGRemote.local.id :: conf.remotes.map(_.id).toList
      Ok(views.html.runTrees(configSvc.plugins, remote, remoteIds,
        treesMap, rootStr, rootMonState, parentId,
        maxLevels, conf.runTreeLevelsDisplay, request.uri))
    }
  }

  def monitorSilenceFetch(cmd: String, until: Option[String], rdr: String) = Action.async {
    val untilTss = until.map(s => SMGState.tssNow + SMGRrd.parsePeriod(s).getOrElse(0))
    monitorApi.silenceFetchCommand(cmd, untilTss).map { b =>
      val redirTo = if (rdr == "") "/runtree"
      Redirect(rdr)
    }
  }

  def proxy(remote: String, path: String) = Action.async {
    val remoteHost = configSvc.config.remotes.find(_.id == remote).map(_.url)
    if (remoteHost.isEmpty) Future { NotFound(s"remote $remote not found") }
    else {
      val remoteUrl = remoteHost.get + "/" + path
      ws.url(remoteUrl).withRequestTimeout(configSvc.config.proxyTimeout).get.map { wsresp =>
        val asStream: InputStream = wsresp.underlying[NettyResponse].getResponseBodyAsStream
        val hdrs = wsresp.allHeaders.map(t => (t._1, t._2.head)).toSeq
        Ok.stream(Enumerator.fromStream(asStream)).withHeaders(hdrs:_*)
      }.recover {
        case x => {
          log.ex(x, "proxy remote error: " + remoteUrl)
          BadGateway(s"Proxy error: $remoteUrl")
        }
      }
    }
  }
}
