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

import play.api.libs.json._


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


  private def parseRemoteParam(remote: String): (List[String], Option[String]) = {
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
  def index(remote :String, lvls: Option[Int]): Action[AnyContent] = Action { implicit request =>
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
  def autoindex(root: String, expandLevels: Int): Action[AnyContent] = Action { implicit request =>
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

  case class DashboardExtraParams (
                                    period: String,
                                    cols: Int,
                                    rows: Int,
                                    pg: Int,
                                    agg: Option[String],
                                    xRemoteAgg: Boolean
                                  )

  /**
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
    */
  case class DashboardParams (
    ix: Option[String],
    px: Option[String],
    sx: Option[String],
    rx: Option[String],
    rxx: Option[String],
    trx: Option[String],
    remote: Option[String],
    agg: Option[String],
    period: Option[String],
    pl: Option[String],
    step: Option[String],
    cols: Option[Int],
    rows: Option[Int],
    pg: Int,
    xagg: String,
    xsort: Int,
    dpp: String,
    d95p: String,
    maxy: Option[String]
  ) {

    def processParams(idx: Option[SMGIndex]): (SMGFilter, DashboardExtraParams) = {
      // use index filter/gopts if index available
      // form is overriding index spec
      
      val myXSort = if (idx.isEmpty || (xsort > 0)) xsort else idx.get.flt.gopts.xsort.getOrElse(0)
      val istep = if (step.getOrElse("") != "") SMGRrd.parseStep(step.get) else None
      val myStep = if (idx.isEmpty || istep.isDefined) istep else idx.get.flt.gopts.step
      val myPl = if (idx.isEmpty || pl.isDefined) pl else idx.get.flt.gopts.pl
      val myDisablePop = if (idx.isEmpty || (dpp == "on")) dpp == "on" else idx.get.flt.gopts.disablePop
      val myDisable95p = if (idx.isEmpty || (d95p == "on")) d95p == "on" else idx.get.flt.gopts.disable95pRule
      val myMaxY = if (idx.isEmpty || maxy.isDefined) optStr2OptDouble(maxy) else idx.get.flt.gopts.maxY

      val myGopts = GraphOptions(step = myStep, pl = myPl, xsort = Some(myXSort),
        disablePop = myDisablePop, disable95pRule = myDisable95p, maxY = myMaxY)

      val myPx = if (idx.isEmpty || px.isDefined) px else idx.get.flt.px
      val mySx = if (idx.isEmpty || sx.isDefined) sx else idx.get.flt.sx
      val myRx = if (idx.isEmpty || rx.isDefined) rx else idx.get.flt.rx
      val myRxx = if (idx.isEmpty || rxx.isDefined) rxx else idx.get.flt.rxx
      val myTrx = if (idx.isEmpty || trx.isDefined) trx else idx.get.flt.trx

      val myRemote = if (idx.isEmpty || remote.isDefined) remote else idx.get.flt.remote

      val flt = SMGFilter(px = myPx, sx = mySx, rx = myRx, rxx = myRxx, trx = myTrx, remote = myRemote, gopts = myGopts)

      val myPeriod = if (idx.isEmpty || period.isDefined)
        period.getOrElse(GrapherApi.defaultPeriod)
      else
        idx.get.period.getOrElse(GrapherApi.defaultPeriod)
      
      val myAgg = if (idx.isEmpty || agg.isDefined) agg else idx.get.aggOp
      val myXRemoteAgg = if (idx.isEmpty || (xagg == "on")) xagg == "on" else idx.get.xAgg
      val myCols = if (idx.isEmpty || cols.isDefined)
        cols.getOrElse(configSvc.config.dashDefaultCols)
      else
        idx.get.cols.getOrElse(configSvc.config.dashDefaultCols)
      val myRows = if (idx.isEmpty || rows.isDefined)
        rows.getOrElse(configSvc.config.dashDefaultRows)
      else
        idx.get.rows.getOrElse(configSvc.config.dashDefaultRows)

      val dep = DashboardExtraParams(period = myPeriod, cols = myCols, rows = myRows, pg = pg, agg = myAgg, xRemoteAgg = myXRemoteAgg)

      (flt,dep)
    }
  }

  private def dashParamsFromMap(m: Map[String, String]): DashboardParams = {
    DashboardParams (
      ix = m.get("ix"),
      px = m.get("px"),
      sx = m.get("sx"),
      rx = m.get("rx"),
      rxx = m.get("rxx"),
      trx = m.get("trx"),
      remote = m.get("remote"),
      agg = m.get("agg"),
      period = m.get("period"),
      pl = m.get("pl"),
      step = m.get("step"),
      cols = m.get("cols").map(_.toInt),
      rows = m.get("rows").map(_.toInt),
      pg = m.get("pg").map(_.toInt).getOrElse(0),
      xagg = m.getOrElse("xagg", ""),
      xsort = m.get("xsort").map(_.toInt).getOrElse(0),
      dpp = m.getOrElse("dpp", ""),
      d95p = m.getOrElse("d95p", ""),
      maxy = m.get("maxy")
    )
  }

  private def dashPostParams(req: Request[AnyContent]): DashboardParams = {
    val myParams = req.body.asFormUrlEncoded.getOrElse(Map()).map(t => (t._1, t._2.head))
    dashParamsFromMap(myParams)
  }

  private def dashGetParams(req: Request[AnyContent]): DashboardParams = {
    val myParams = req.queryString.map(t => (t._1, t._2.head))
    dashParamsFromMap(myParams)
  }

  /**
    * Display dashboard page (filter and graphs)
    * @return
    */
  def dash(): Action[AnyContent] = Action.async { implicit request =>

    val myErrors = ListBuffer[String]()

    val dps = if (request.method == "POST") {
      myErrors += "This page URL is not share-able because the resulting URL would be too long."
      dashPostParams(request)
    }
    else
      dashGetParams(request)

    val showMs = msEnabled(request)
    val idx = if (dps.ix.nonEmpty) {
      val idxOpt = smg.getIndexById(dps.ix.get)
      if (idxOpt.isEmpty) {
        myErrors += s"Index with id ${dps.ix.get} not found"
      }
      idxOpt
    } else None
    val parentIdx: Option[SMGIndex] = if (idx.isDefined && idx.get.parentId.isDefined) {
      smg.getIndexById(idx.get.parentId.get)
    } else None
    
    val (flt, dep) = dps.processParams(idx)

    val conf = configSvc.config
    val availRemotes = if (conf.remotes.nonEmpty)
      Seq(SMGRemote.local) ++ conf.remotes ++ Seq(SMGRemote.wildcard)
    else
      Seq[SMGRemote]()

    // filter results and slice according to pagination
    var maxPages = 1
    var tlObjects = 0
    val limit = dep.cols * dep.rows
    val objsSlice = if (limit <= 0) {
      Seq()
    } else {
      val offset = dep.pg * limit
      val filteredObjects = smg.getFilteredObjects(flt)
      tlObjects = filteredObjects.size
      maxPages = (tlObjects / limit) + (if ((tlObjects % limit) == 0) 0 else 1)
      val sliced = filteredObjects.slice(offset, offset + limit)
      if (flt.gopts.xsort.getOrElse(0) > 0)
        xsortObjectViews(sliced, flt.gopts.xsort.get - 1, dep.period)
      else
        sliced
    }

    // get two futures - one for the images we want and the other for the respective monitorStates
    val (futImages, futMonitorStates) = if (objsSlice.isEmpty) {
      ( Future { Seq() },
        Future { Map() } )
    } else if (dep.agg.nonEmpty) {
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
      val aggObjs = for (v <- orderedVars.toList) yield SMGAggObjectView.build(byGraphVars(v).toList, dep.agg.get)
      // if we are not graphing cross-remote, every ag object defined from a cross-remote filter can
      // result in multiple images (one per remote) and we want the monitoring state per resulting image
      val monObjsSeq = if (!dep.xRemoteAgg) {
        aggObjs.flatMap { ago =>
          ago.splitByRemoteId.values.toList
        }
      } else aggObjs
      val aggFutureSeqs = for (ao <- aggObjs) yield smg.graphAggObject(ao, Seq(dep.period), flt.gopts, dep.xRemoteAgg)
      (Future.sequence(aggFutureSeqs).map { sofs => sofs.flatten },
        if (showMs) monitorApi.objectViewStates(monObjsSeq) else Future { Map() } )
    } else {
      ( smg.graphObjects(objsSlice, Seq(dep.period), flt.gopts),
        if (showMs) monitorApi.objectViewStates(objsSlice) else Future { Map() } )
    }

    // We need both the (future) images and monitor states resolved before responding
    Future.sequence(Seq(futImages, futMonitorStates)).map { mySeq =>
      val lst = mySeq.head.asInstanceOf[List[SMGImageView]]
      val monStatesByImgView = mySeq(1).asInstanceOf[Map[String,Seq[SMGMonState]]]

      val byRemoteMap = lst.groupBy( img => img.remoteId.getOrElse("") )
      val byRemote = (List("") ++ remotes.configs.map(rc => rc.remote.id)).
        filter(rid => byRemoteMap.contains(rid)).map( rid => (rid, byRemoteMap(rid)) )
      //preserve order
      val monOverviewOids = objsSlice.map(_.id)
      // XXX make sure we find agg objects and their op even if not specified in url params but e.g. coming from plugin
      val myAggOp = if (dep.agg.isDefined) dep.agg else lst.find(_.obj.isAgg).map(_.obj.asInstanceOf[SMGAggObjectView].op)
      val errorsOpt = if (myErrors.isEmpty) None else Some(myErrors.mkString(", "))
      Ok(
        views.html.filterResult(configSvc.plugins, idx, parentIdx, byRemote, flt, dep, myAggOp, maxPages,
          lst.size, objsSlice.size, tlObjects, availRemotes,
          flt.gopts, showMs, monStatesByImgView, monOverviewOids, errorsOpt, conf)
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
  def show(oid:String, cols: Int, dpp: String, d95p: String,
           maxy: Option[String]): Action[AnyContent] = Action.async { implicit request =>
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
          val ixes = smg.objectIndexes(obj)
          Ok(views.html.show(configSvc.plugins, obj, lst, cols, configSvc.config.rrdConf.imageCellWidth, gopts, showMs, ms, ixes))
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
  def showAgg(ids:String, op:String, title:Option[String], cols: Int, dpp: String, d95p: String,
              maxy: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    val showMs = msEnabled(request)
    val gopts = GraphOptions(step = None, pl = None, xsort = None,
      disablePop = dpp == "on", disable95pRule = d95p == "on", maxY = optStr2OptDouble(maxy))
    val idLst = ids.split(',')
    val objList = idLst.filter( id => smg.getObjectView(id).nonEmpty ).map(id => smg.getObjectView(id).get)
    if (objList.isEmpty)
      Future { }.map { _ => NotFound("object ids not found") }
    else {
      val byRemote = objList.groupBy(o => SMGRemote.remoteId(o.id))
      val aobj = SMGAggObjectView.build(objList, op, title)
      val gfut = smg.graphAggObject(aobj, smg.detailPeriods, gopts, byRemote.keys.size > 1)
      val mfut = if (showMs) monitorApi.objectViewStates(objList) else Future { Map() }
      val ixes = smg.objectIndexes(aobj)
      Future.sequence(Seq(gfut,mfut)).map { t =>
        val lst = t.head.asInstanceOf[Seq[SMGImageView]]
        val ms = t(1).asInstanceOf[Map[String,Seq[SMGMonState]]].flatMap(_._2).toList
        Ok(views.html.show(configSvc.plugins, aobj, lst, cols, configSvc.config.rrdConf.imageCellWidth, gopts, showMs, ms, ixes))
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
  def fetch(oid: String, r:Option[Int], s: Option[String], e: Option[String],
            d:Boolean): Action[AnyContent] = Action.async {
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
  def fetchAgg(ids:String, op:String, r:Option[Int], s: Option[String], e: Option[String],
               d:Boolean): Action[AnyContent] = Action.async {
    val idLst = ids.split(',')
    val objList = idLst.filter( id => smg.getObjectView(id).nonEmpty ).map(id => smg.getObjectView(id).get)
    if (objList.isEmpty)
      Future { }.map { _ => NotFound("object ids not found") }
    else {
      val aobj = SMGAggObjectView.build(objList, op)
      val params = SMGRrdFetchParams(r, s, e, filterNan = false)
      smg.fetchAgg(aobj, params).map { ret =>
        fetchCommon(aobj, d, ret)
      }
    }
  }

  private def fetchCommon(ov: SMGObjectView, d: Boolean, ret: Seq[SMGRrdRow]): Result = {
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
          val shortIds = SMGAggObjectView.stripCommonStuff('.', aov.objs.map(o => o.id)).iterator
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
  def runJob(interval: Int, id: Option[String]): Action[AnyContent] = Action {
    if (id.isEmpty || (id.get == "")) {
      smg.run(interval)
      Ok("OK")
    } else {
      if (smg.runCommandsTree(interval, id.get))
        Ok(s"OK - sent message for ${id.get}")
      else
        NotFound(s"ERROR - did not find commands tree with root ${id.get}")
    }
  }

  /**
    * Reload local config from disk and propagate the reload command to all configured remotes.
    *
    * @return
    */
  def reloadConf: Action[AnyContent] = Action {
    configSvc.reload()
    remotes.notifyMasters()
    if (configSvc.config.reloadSlaveRemotes) {
      remotes.notifySlaves()
    }
    remotes.fetchConfigs()
    val myNewConf = configSvc.config
    val retStr = if (myNewConf.allErrors.isEmpty)
      s"OK - no issues detected (${myNewConf.viewObjects.size} view objects defined)"
    else s"WARNING - some issues detected (${myNewConf.viewObjects.size} view objects defined):\n\n" +
      configSvc.config.allErrors.mkString("\n")
    Ok(retStr)
  }

  def configStatus:  Action[AnyContent] = Action {
    val myConf = configSvc.config
    val retStr = if (myConf.allErrors.isEmpty)
      s"OK - no issues detected (${myConf.viewObjects.size} view objects defined)"
    else s"WARNING - some issues detected (${myConf.viewObjects.size} view objects defined):\n\n" +
      configSvc.config.allErrors.mkString("\n")
    Ok(retStr)
  }

  def pluginIndex(pluginId: String): Action[AnyContent] = Action { implicit request =>
    val httpParams = (if (request.method == "POST") {
      request.body.asFormUrlEncoded.getOrElse(Map())
    } else {
      request.queryString
    }).map { case (k,v) => k -> v.mkString }
    configSvc.plugins.find( p => p.pluginId == pluginId) match {
      case Some(plugin) => {
        Ok(views.html.pluginIndex(plugin, plugin.htmlContent(httpParams), configSvc.plugins))
      }
      case None => Ok("Plugin not found - " + pluginId)
    }
  }

  def userSettings(): Action[AnyContent] = Action { implicit request =>
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

  def inspect(id: String): Action[AnyContent] = Action {
    if (SMGRemote.isRemoteObj(id)) {
      // XXX fow now just redirect to the remore url
      val rid = SMGRemote.remoteId(id)
      val remote = configSvc.config.remotes.find(_.id == rid)
      if (remote.isDefined) {
        Redirect(s"/proxy/${remote.get.id}/inspect/" + SMGRemote.localId(id))
      } else NotFound("Remote object not found")
    } else {
      val ov = smg.getObjectView(id)
      val ou = if (ov.isDefined) ov.get.refObj else None
      if (ou.isDefined) {
        val ac = configSvc.config.objectAlertConfs.get(ou.get.id)
        val ncUnk = ou.get.notifyConf
        val nc  = configSvc.config.objectNotifyConfs.get(ou.get.id)
        val ncmdsByVar = ou.get.vars.indices.map { vix =>
          val bySev = Seq(SMGMonNotifySeverity.ANOMALY, SMGMonNotifySeverity.WARNING,
            SMGMonNotifySeverity.UNKNOWN, SMGMonNotifySeverity.CRITICAL).map { sev =>
            val cmdBackoff = configSvc.objectVarNotifyCmdsAndBackoff(ou.get, Some(vix), sev)
            (sev, cmdBackoff._1, cmdBackoff._2)
          }.toList
          (vix, bySev)
        }.toList
        val notifyStrikesObj = configSvc.objectVarNotifyStrikes(ou.get, None)
        val notifyStrikesVars = ou.get.vars.indices.map( vix => configSvc.objectVarNotifyStrikes(ou.get, Some(vix)))
        val hstate =  monitorApi.inspectObject(ov.get)
        val pfs = if (ou.get.preFetch.isDefined) {
          val lb = ListBuffer[(SMGPreFetchCmd, String)]()
          var cur = configSvc.config.findPreFetchCmd(ou.get.preFetch.get)
          while (cur.isDefined && (lb.size < 100)) {
            val pfState = monitorApi.inspectPf(cur.get._1.id, ou.get.interval).getOrElse("ERROR: No state available")
            lb += ((cur.get._1, cur.get._2.map(plid => s"(Plugin: $plid) ").getOrElse("") + pfState))
            cur = cur.get._1.preFetch.flatMap(ppf => configSvc.config.findPreFetchCmd(ppf))
          }
          lb.toList.reverse
        } else List()
        Ok(views.html.inspectObject(ov.get, pfs, ac, ncUnk, nc, ncmdsByVar, Seq(notifyStrikesObj) ++ notifyStrikesVars, hstate))
      } else NotFound("Object not found")
    }
  }

  val DEFAULT_INDEX_HEATMAP_MAX_SIZE = 300

  def monitorIndexSvg(ixid: String): Action[AnyContent] = Action.async {
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

  def monitorObjectsSvgHtml(): Action[AnyContent] = Action.async { request =>
    val oidsParam = request.body.asFormUrlEncoded.get.get("oids")
    if (oidsParam.isEmpty)
      Future {  Ok(views.html.monitorSvgNotFound()) }
    else {
      val oids = oidsParam.get.head.split(",")
      val ovs = oids.distinct.map { oid => smg.getObjectView(oid) }.filter(_.isDefined).map(_.get)
      monitorApi.objectViewStates(ovs).map { byObj =>
        val mss = ovs.flatMap(ov => byObj.getOrElse(ov.id, Seq()))
        Ok(views.html.monitorSvgObjects(mss.toSeq, None))
      }
    }
  }

  def monitorProblems(remote: String,
                      ms: Option[String],
                      soft: Option[String],
                      ackd: Option[String],
                      slncd: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    val (availRemotes, rmtOpt) = parseRemoteParam(remote)
    val minSev = ms.map{ s => SMGState.withName(s) }.getOrElse(SMGState.E_ANOMALY)
    val inclSoft = soft.getOrElse("off") == "on"
    val inclAck = ackd.getOrElse("off") == "on"
    val inclSlnc = slncd.getOrElse("off") == "on"
    val flt = SMGMonFilter(rx = None, rxx = None, minState = Some(minSev),
      includeSoft = inclSoft, includeAcked = inclAck, includeSilenced = inclSlnc)
    val availStates = (SMGState.values - SMGState.OK).toSeq.sorted.map(_.toString)
    monitorApi.states(rmtOpt, flt).map { msr =>
      Ok(views.html.monitorProblems(configSvc.plugins, availRemotes, availStates, rmtOpt,
        msr, flt, request.uri))
    }

  }

  def monitorSilenced(): Action[AnyContent] = Action.async { implicit request =>
    monitorApi.silencedStates().map { seq =>
      Ok(views.html.monitorSilenced(configSvc.plugins, seq, request.uri))
    }
  }


  val DEFAULT_HEATMAP_MAX_SIZE = 1800

  def monitorHeatmap: Action[AnyContent] = Action.async { implicit request =>
    val params = request.queryString
    val flt = SMGFilter.matchAll //SMGFilter.fromParams(params)
    monitorApi.heatmap(flt,
      Some(params.get("maxSize").map(_.head.toInt).getOrElse(DEFAULT_HEATMAP_MAX_SIZE)),
      params.get("offset").map(_.head.toInt),
      params.get("limit").map(_.head.toInt)).map { data =>
      Ok(views.html.monitorHeatmap(configSvc.plugins, data))
    }
  }


  val DEFAULT_LOGS_SINCE = "24h"
  val DEFAULT_LOGS_LIMIT = 200

  def monitorLog(remote: String, p: Option[String], l: Option[Int], ms: Option[String], soft: Option[String],
                 ackd: Option[String], slncd: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    val (availRemotes, rmtOpt) = parseRemoteParam(remote)
    val minSev = ms.map{ s => SMGState.withName(s) }.getOrElse(SMGState.E_VAL_WARN)
    val period = p.getOrElse(DEFAULT_LOGS_SINCE)
    val limit = l.getOrElse(DEFAULT_LOGS_LIMIT)
    val inclSoft = soft.getOrElse("off") == "on"
    val includeAckd = ackd.getOrElse("off") == "on"
    val includeSlncd = slncd.getOrElse("off") == "on"
    monitorApi.monLogApi.getSince(period, rmtOpt, limit, Some(minSev), inclSoft, includeAckd, includeSlncd).map { logs =>
      Ok(views.html.monitorLog(configSvc.plugins, availRemotes, rmtOpt, SMGState.values.toList.map(_.toString),
        Some(minSev.toString), period, DEFAULT_LOGS_SINCE, limit, DEFAULT_LOGS_LIMIT,
        inclSoft = inclSoft, inclAckd = includeAckd, inclSlncd = includeSlncd, logs))
    }
  }


  val TREES_PAGE_DFEAULT_LIMIT = 200

  def monitorTrees(remote: String,
                   rx: Option[String],
                   rxx: Option[String],
                   ms: Option[String],
                   hsoft: String,
                   hackd: String,
                   hslncd: String,
                   rid: Option[String],
                   pg: Int,
                   lmt: Option[Int],
                   silenceAllUntil: Option[String],
                   curl: Option[String]
                  ): Action[AnyContent] = Action.async { implicit request =>
    val conf = configSvc.config
    val flt = SMGMonFilter(rx, rxx, ms.map(s => SMGState.withName(s)),
      includeSoft = hsoft == "off", includeAcked = hackd == "off",
      includeSilenced = hslncd == "off")
    val mySlncUntil = silenceAllUntil.map(slunt => SMGState.tssNow + SMGRrd.parsePeriod(slunt).getOrElse(0))
    if (mySlncUntil.isDefined && curl.isDefined) {
      monitorApi.silenceAllTrees(remote, flt, rid, mySlncUntil.get).map { ret =>
        if (ret)
          Redirect(curl.get)
        else
          NotFound("Some error occured")
      }
    } else {
      val myLimit = Math.max(2, lmt.getOrElse(TREES_PAGE_DFEAULT_LIMIT))
      val offs = pg * myLimit
      val remoteIds = SMGRemote.local.id :: conf.remotes.map(_.id).toList
      val myPg = Math.max(0, pg)
      monitorApi.monTrees(remote, flt, rid, myPg, myLimit).map { t =>
        val seq = t._1
        val maxPg = t._2
        Ok(views.html.monitorStateTrees(configSvc.plugins, remote, remoteIds, flt, rid, seq, myPg, maxPg,
          myLimit, TREES_PAGE_DFEAULT_LIMIT, request.uri))
      }
    }
  }

  def monitorRunTree(remote: String, root: Option[String],
                     lvls: Option[Int]): Action[AnyContent] = Action.async { implicit request =>
    val conf = configSvc.config
    val rootStr = root.getOrElse("")
    val treesMapFut = if (remote == "") {
      Future {
        conf.fetchCommandTreesWithRoot(root)
      }
    } else {
      remotes.monitorRunTree(remote, root)
    }

    treesMapFut.map { treesMap =>
      val parentId = if (rootStr == "") None else {
        treesMap.values.flatten.find { t => t.node.id == rootStr }.flatMap(_.node.preFetch)
      }
      val maxLevels = if (rootStr != "") conf.MAX_RUNTREE_LEVELS else lvls.getOrElse(conf.runTreeLevelsDisplay)
      val remoteIds = SMGRemote.local.id :: conf.remotes.map(_.id).toList
      Ok(views.html.runTrees(configSvc.plugins, remote, remoteIds,
        treesMap, rootStr, parentId, maxLevels, conf.runTreeLevelsDisplay, request.uri))
    }
  }

  def monitorAck(id: String, curl: String): Action[AnyContent] = Action.async {
    monitorApi.acknowledge(id).map { ret =>
      Redirect(curl).flashing( if (ret) {
        "success" -> s"Acknowledged $id problem(s)"
      } else {
        "error" -> s"Some unexpected error occured while acknowledging $id problem(s)"
      })
    }
  }

  def monitorUnack(id: String, curl: String): Action[AnyContent] = Action.async {
    monitorApi.unacknowledge(id).map { ret =>
      Redirect(curl).flashing( if (ret) {
        "success" -> s"Removed acknowledgement for $id problem(s)"
      } else {
        "error" -> s"Some unexpected error occured while removing acknowledgement for $id problem(s)"
      })
    }
  }

  def monitorSilence(id: String, slunt: String, curl: String): Action[AnyContent] = Action.async {
    val untilTss = SMGState.tssNow + SMGRrd.parsePeriod(slunt).getOrElse(0)
    monitorApi.silence(id, untilTss).map { ret =>
      Redirect(curl).flashing( if (ret) {
        "success" -> s"Silenced $id until ${SMGState.formatTss(untilTss)}"
      } else {
        "error" -> s"Some unexpected error occured while silencing $id"
      })
    }
  }

  def monitorUnsilence(id: String, curl: String): Action[AnyContent] = Action.async {
    monitorApi.unsilence(id).map { ret =>
      Redirect(curl).flashing( if (ret) {
        "success" -> s"Unsilenced $id"
      } else {
        "error" -> s"Some unexpected error occured while unsilencing $id"
      })
    }
  }

  def monitorAckList(): Action[AnyContent] = Action.async { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head.split(",")
    val curl = params("curl").head
    monitorApi.acknowledgeList(ids).map { ret =>
      Redirect(curl).flashing( if (ret) {
        "success" -> s"Acknowledged ${ids.length} objects problems"
      } else {
        "error" -> s"Some unexpected error occured while acknowledging ${ids.length} objects problem"
      })
    }
  }

  def monitorSilenceList(): Action[AnyContent] = Action.async { request =>
    val params = request.body.asFormUrlEncoded.get
    val ids = params("ids").head.split(",")
    val curl = params("curl").head
    val slunt = params("slunt").head
    val untilTss = SMGState.tssNow + SMGRrd.parsePeriod(slunt).getOrElse(0)
    monitorApi.silenceList(ids, untilTss).map { ret =>
      Redirect(curl).flashing( if (ret) {
        "success" -> s"Silenced ${ids.length} objects until ${SMGState.formatTss(untilTss)}"
      } else {
        "error" -> s"Some unexpected error occured while silencing ${ids.length} objects"
      })
    }
  }

  def monitorSilenceIdx(ix: String, slunt: String, curl: String): Action[AnyContent] = Action.async { request =>
    val untilTss = SMGState.tssNow + SMGRrd.parsePeriod(slunt).getOrElse(0)
    val idx = smg.getIndexById(ix)
    if (idx.isDefined) {
      val objs = smg.getFilteredObjects(idx.get.flt)
      monitorApi.silenceList(objs.map(_.id), untilTss).map { ret =>
        Redirect(curl).flashing( if (ret) {
          "success" -> s"Silenced all objects matching index: ${idx.get.title} (${idx.get.id})"
        } else {
          "error" -> s"Some unexpected error occured while silencing objects matching index: ${idx.get.title} (${idx.get.id})"
        })
      }
    } else {
      Future {
        Redirect(curl).flashing(
          "error" -> s"Index with id $ix not found"
        )
      }
    }
  }

  private def remoteFlashSuffix(remote: String) = {
    if (remote == SMGRemote.wildcard.id)
      " for all remotes"
    else if (remote == SMGRemote.local.id)
      ""
    else
      s" for $remote"
  }


  def monitorMute(remote: String, curl: String): Action[AnyContent] = Action.async {
    monitorApi.mute(remote).map { ret =>
      Redirect(curl).flashing( if (ret) {
        "success" -> s"Mute successful - notfications disabled${remoteFlashSuffix(remote)}"
      } else {
        "error" -> s"Mute failed - some unexpected error occured while disabling notifications${remoteFlashSuffix(remote)}"
      })
    }
  }

  def monitorUnmute(remote: String, curl: String): Action[AnyContent] = Action.async {
    monitorApi.unmute(remote).map { ret =>
      Redirect(curl).flashing( if (ret) {
        "success" -> s"Unmute successful - notfiications enabled${remoteFlashSuffix(remote)}"
      } else {
        "error" -> s"Unmute failed - some unexpected error occured while enabling notifications${remoteFlashSuffix(remote)}"
      })
    }
  }

  private val saveStatesSyncObject = new Object()
  private var saveStatesIsRunning: Boolean = false

  def monitorSaveStates(): Action[AnyContent] = Action {
    val shouldRun = (!actorSystem.isTerminated) && saveStatesSyncObject.synchronized {
      if (saveStatesIsRunning) {
        false
      } else {
        saveStatesIsRunning = true
        true
      }
    }
    if (shouldRun) {
      Future {
        try {
          monitorApi.saveStateToDisk()
        } finally {
          saveStatesIsRunning = false
        }
      }
      Ok("OK: saveStateToDisk started\n")
    } else {
      NotFound("ERROR: saveStateToDisk is already running or system is terminated\n")
    }
  }

  /**
    * proxy GET requests to "slave" SMG instances (for images etc).
    * Note that it is better to setup a "native" reverse proxy (e.g. apache/nginx) in front of
    * SMG for better performance in which case this method should be unused. Check docs for more info
    * 
    * @param remote
    * @param path
    * @return
    */
  def proxy(remote: String, path: String): Action[AnyContent] = Action.async {
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

  val JSON_PERIODS_RESPONSE = Ok(Json.toJson(GrapherApi.detailPeriods))

  def jsonPeriods() = Action {
    JSON_PERIODS_RESPONSE
  }

  val DEFAULT_SEARCH_RESULTS_LIMIT = 500

  def search(q: Option[String], lmt: Option[Int]): Action[AnyContent] = Action { implicit request =>
    val maxRes = lmt.getOrElse(DEFAULT_SEARCH_RESULTS_LIMIT)
    val sres = smg.searchCache.search(q.getOrElse(""), maxRes + 1)
    Ok(views.html.search(q = q.getOrElse(""),
      res = sres.take(maxRes),
      hasMore = sres.size == maxRes + 1,
      lmt = maxRes,
      defaultLmt = DEFAULT_SEARCH_RESULTS_LIMIT,
      plugins = configSvc.plugins))
  }

  def jsonTrxTokens(q: String, remote: Option[String]) = Action {
    val rmtId = remote.getOrElse("")
    val tkns = smg.searchCache.getTrxTokens(q, rmtId)
    Ok(Json.toJson(tkns))
  }

  def jsonRxTokens(q: String, remote: Option[String]) = Action {
    val rmtId = remote.getOrElse("")
    val tkns = smg.searchCache.getRxTokens(q, rmtId)
    Ok(Json.toJson(tkns))
  }

  def jsonSxTokens(q: String, remote: Option[String]) = Action {
    val rmtId = remote.getOrElse("")
    val tkns = smg.searchCache.getSxTokens(q, rmtId)
    Ok(Json.toJson(tkns))
  }

  def jsonPxTokens(q: String, remote: Option[String]) = Action {
    val rmtId = remote.getOrElse("")
    val tkns = smg.searchCache.getPxTokens(q, rmtId)
    Ok(Json.toJson(tkns))
  }

  def jsonCmdTokens(q: String, remote: Option[String]) = Action {
    val rmtId = remote.getOrElse("")
    val tkns = smg.searchCache.getPfRxTokens(q, rmtId)
    Ok(Json.toJson(tkns))
  }

}
