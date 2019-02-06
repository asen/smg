package com.smule.smg.cdash

import com.smule.smg.GrapherApi
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGAggGroupBy, SMGFilter}
import com.smule.smg.monitor._
import com.smule.smg.remote.{SMGRemote, SMGRemotesApi}
import javax.inject.{Inject, Singleton}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@Singleton
class CDashboardSvc @Inject()(configSvc: SMGConfigService,
                              smg: GrapherApi,
                              remotes: SMGRemotesApi,
                              monitorApi: SMGMonitorApi,
                              monLogApi: SMGMonitorLogApi,
                              notifSvc: SMGMonNotifyApi) extends CDashboardApi {

  implicit private val myEc: ExecutionContext = configSvc.executionContexts.rrdGraphCtx

  override def getDashboardData(cdid: String): Future[Option[CDashboardData]] = {
    val cdashConfOpt = configSvc.config.customDashboards.find(_.id == cdid)
    if (cdashConfOpt.isEmpty)
      Future {
        None
      } // TODO
    else
      dashboardDataFromConfig(cdashConfOpt.get).map(Some(_))
  }

  private def dashboardDataFromConfig(cf: CDashboardConfig): Future[CDashboardData] = {
    val futs = cf.items.map { itm =>
      itm.itemType match {
        case CDashItemType.IndexGraphs => getIndexGraphs(itm)
        case CDashItemType.IndexStates => getIndexStates(itm)
        case CDashItemType.MonitorProblems => getMonitorProblems(itm)
        case CDashItemType.MonitorLog => getMonitorLogs(itm)
        case CDashItemType.Plugin => getPlugin(itm)
        case CDashItemType.External => getExternal(itm)
      }
    }
    Future.sequence(futs).map { seq =>
      CDashboardData(cf, seq)
    }
  }

  private def errorItem(itm: CDashConfigItem, msg: String = "") = Future {
    itm.asErrorItem(msg)
  }

  private def getIndexGraphs(itm: CDashConfigItem): Future[CDashItem] = {
    val indexId = itm.getDataStr("ix").getOrElse("")
    val limit = Try(itm.getDataStr("limit").get.toInt).getOrElse(1)

    val idxOpt = smg.getIndexById(indexId)
    if (idxOpt.isEmpty)
      errorItem(itm, s"Index not found: $indexId")
    else {
      val idx = idxOpt.get
      val filteredObjects = smg.getFilteredObjects(SMGFilter.matchAll, Some(idx))
      val tlObjects = filteredObjects.size
      val indexLimit = idx.cols.getOrElse(configSvc.config.dashDefaultCols) *
        idx.rows.getOrElse(configSvc.config.dashDefaultRows)
      val objsSlice = filteredObjects.take(indexLimit)
      lazy val aggObjs = idx.aggOp.map(agg => smg.buildAggObjects(objsSlice, agg,
        idx.aggGroupBy.getOrElse(SMGAggGroupBy.defaultGroupBy)))
      val myPeriod = idx.period.getOrElse(GrapherApi.defaultPeriod)
      val futImages = if (idx.aggOp.nonEmpty && objsSlice.nonEmpty) {
        smg.graphAggObjects(aggObjs.get, myPeriod, idx.flt.gopts,
          idx.aggOp.get, idx.xRemoteAgg)
      } else smg.graphObjects(objsSlice, Seq(myPeriod), idx.flt.gopts)
      futImages.map { imgs =>
        CDashItemIndexGraphs(itm, idx, imgs.take(limit))
      }
    }
  }

  private def getIndexStates(itm: CDashConfigItem): Future[CDashItem] = {
    val indexIds = itm.getDataStrSeq("ixes")
    val imgWidth = itm.getDataStr("img_width").getOrElse("")
    val idxes = indexIds.flatMap(indexId => smg.getIndexById(indexId))
    Future { CDashItemIndexStates(itm, imgWidth, idxes) }
  }

  private def getMonitorProblems(itm: CDashConfigItem): Future[CDashItem] = {
    val ms = itm.getDataStr("ms")
    val soft = itm.getDataStr("soft")
    val slncd = itm.getDataStr("slncd")
    val remote = itm.getDataStr("remote")
    val limit = Try(itm.getDataStr("limit").get.toInt).getOrElse(1)

    val minSev = ms.map { s => SMGState.fromName(s) }.getOrElse(SMGState.ANOMALY)
    val inclSoft = soft.getOrElse("off") == "on"
    val inclSlnc = slncd.getOrElse("off") == "on"
    //val inclAck = ackd.getOrElse("off") == "on"
    val inclAck = inclSlnc
    val flt = SMGMonFilter(rx = None, rxx = None, minState = Some(minSev),
      includeSoft = inclSoft, includeAcked = inclAck, includeSilenced = inclSlnc)
    val availStates = (SMGState.values - SMGState.OK).toSeq.sorted.map(_.toString)
    val myRemotes = if (remote.isEmpty) {
      Seq(SMGRemote.wildcard.id)
    } else Seq(remote.get)
    monitorApi.states(myRemotes, flt).map { msr =>
      // TODO consider limit
      CDashItemMonitorProblems(itm, flt, msr)
    }
  }

  private def getMonitorLogs(itm: CDashConfigItem): Future[CDashItem] = {
    val ms = itm.getDataStr("ms")
    val soft = itm.getDataStr("soft")
    val slncd = itm.getDataStr("slncd")
    val remote = itm.getDataStr("remote")
    val limit = Try(itm.getDataStr("limit").get.toInt).getOrElse(SMGMonitorLogApi.DEFAULT_LOGS_LIMIT)
    val period = itm.getDataStr("period").getOrElse(SMGMonitorLogApi.DEFAULT_LOGS_SINCE)
    val rx = itm.getDataStr("rx")
    val rxx = itm.getDataStr("rxx")

    val myRemotes = if (remote.isEmpty) {
      Seq(SMGRemote.wildcard.id)
    } else Seq(remote.get)
    val minSev = ms.map { s => SMGState.fromName(s) }.getOrElse(SMGState.WARNING)
    val inclSoft = soft.getOrElse("off") == "on"
    val includeSlncd = slncd.getOrElse("off") == "on"
    // val includeAckd = ackd.getOrElse("off") == "on"
    val includeAckd = includeSlncd
    val flt = SMGMonitorLogFilter(
      periodStr = period,
      rmtIds = myRemotes,
      limit = limit,
      minSeverity = Some(minSev),
      inclSoft = inclSoft,
      inclAcked = includeAckd,
      inclSilenced = includeSlncd,
      rx = rx,
      rxx = rxx)
    monitorApi.monLogApi.getAll(flt).map { logs =>
      CDashItemMonitortLog(itm, flt, logs)
    }
  }

  private def getExternal(itm: CDashConfigItem): Future[CDashItem] = {
    val url = itm.getDataStr("url").getOrElse("ERROR")
    Future {
      CDashItemExternal(itm, url)
    }
  }

  private def getPlugin(itm: CDashConfigItem): Future[CDashItem] = {
    val pluginId = itm.getDataStr("plugin_id").getOrElse("")
    val pluginOpt = configSvc.pluginsById.get(pluginId)
    if (pluginOpt.isEmpty)
      errorItem(itm, s"Invalid plugin id: $pluginId")
    else {
      val futOpt = pluginOpt.get.cdashItem(itm)
      if (futOpt.isEmpty) {
        errorItem(itm, s"Plugin $pluginId returned no data")
      } else
        futOpt.get
    }
  }
}

