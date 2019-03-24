package com.smule.smg

import com.smule.smg.config.SMGAutoIndex
import com.smule.smg.core.{SMGAggGroupBy, SMGFilter, SMGIndex, SMGObjectView}
import com.smule.smg.grapher.{GraphOptions, SMGAggObjectView, SMGImageView, SMGImageViewsGroup}
import com.smule.smg.remote.{SMGRemote, SMGRemotesApi}
import com.smule.smg.rrd.{SMGRrdFetchParams, SMGRrdRow}
import com.smule.smg.search.SMGSearchCache

import scala.concurrent.Future

/**
  * The SMGrapher API
  */
trait GrapherApi {

  /**
    * Execute a fetch + update run for given interval (to be called regularly by scheduler)
    * @param interval - interval in seconds
    */
  def run(interval:Int):Unit

  /**
    * Trigger running given commands tree on-demand (outside regular interval runs e.g. for testing)
    * @param interval - interval under which the commands tree normally runs.
    * @param cmdId - top-level command id to execute and then all child commands as defined
    * @return - true if matching interval and command were found and false otherwise.
    */
  def runCommandsTree(interval: Int, cmdId: String): Boolean

  /**
    * Get Top Level configured indexes, grouped by remote
    * @return - sequence of tuples containing the remote id and a sequence of idnexes
    */
  def getTopLevelIndexesByRemote(rmtIds: Seq[String]): Seq[(SMGRemote, Seq[SMGIndex])]

  /**
    * Get Top Level automatically discovered (by id) indexes
    * @return - sequence of indexes
    */
  def getAutoIndex: SMGAutoIndex

  /**
    * Get a configured index from its id
    * @param id - index id to lookup
    * @return - some SMGIndex if found, None otherwise
    */
  def getIndexById(id: String): Option[SMGIndex]

  /**
    * Get a SMGObjectView from its object id
    * @param id - id to lookup
    * @return - Some SMGObjectView if found, None otherwise
    */
  def getObjectView(id:String): Option[SMGObjectView]

  /**
    * Get a sequence of SMGObjectViews (each representing an object from which a graph image can be produced)
    * from given filter
    * @param filter - filter to use
    * @return - sequence of matching objects
    */
  def getFilteredObjects(filter: SMGFilter, ix: Option[SMGIndex]): Seq[SMGObjectView]


  /**
    * Asynchronous call to graph a SMGObjectView for a given sequence of periods.
    * @param obj - SMGRrdObject to graph
    * @param periods - sequence of strings each representing a period to graph
    * @return - future sequence of SMG image objects
    */
  def graphObject(obj:SMGObjectView, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]]

  /**
    * Asynchronous call to graph multiple SMGObjectViews
    * for a given sequence of periods.
    * @param lst - list of SMGRrdObject to graph
    * @param periods - sequence of strings each representing a period to graph
    * @return - future sequence of SMG image objects
    */
  def graphObjects(lst: Seq[SMGObjectView], periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]]

  /**
    * Asynchronous call to graph a SMGAggObjectView (representing an aggregate view from multiple SMGObjectViews,
    * each representing single rrd database), for a given sequence of periods.
    *
    * @param aobj - an aggregate object to graph
    * @param periods - list of periods to graph for
    * @param xRemote - whether to download remote rrds and aggregate cross-remote
    * @return - future sequence of SMG image objects
    */
  def graphAggObject(aobj: SMGAggObjectView, periods: Seq[String], gopts: GraphOptions, xRemote: Boolean): Future[Seq[SMGImageView]]

  //TODO
  def buildAggObjects(objsSlice: Seq[SMGObjectView], aggOp: String,
                      groupBy: SMGAggGroupBy.Value): Seq[SMGAggObjectView]

  //TODO
  def graphAggObjects(aggObjs: Seq[SMGAggObjectView], period: String, gopts: GraphOptions,
                      aggOp: String, xRemoteAgg: Boolean): Future[Seq[SMGImageView]]

  //TODO
  def groupAndGraphObjects(objsSlice: Seq[SMGObjectView], period: String, gopts: GraphOptions,
                           aggOp: Option[String], xRemoteAgg: Boolean,
                           groupBy: SMGAggGroupBy.Value): Future[Seq[SMGImageView]]

    /**
    * Asynchronous call to graph a SMGObjectView for the set of
    * pre-defined default periods.
    * @param obj - SMGObjectView to graph
    * @return - future sequence of SMG image objects
    */
  def getObjectDetailGraphs(obj:SMGObjectView, gopts: GraphOptions): Future[Seq[SMGImageView]]

  /**
    * TODO
    * @param obj
    * @param params
    * @return
    */
  def fetch(obj: SMGObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]]

  /**
    * TODO
    * @param objs
    * @param params
    * @return
    */
  def fetchMany(objs: Seq[SMGObjectView], params: SMGRrdFetchParams): Future[Seq[(String, Seq[SMGRrdRow])]]

  /**
    * TODO
    * @param aobj
    * @param params
    * @return
    */
  def fetchAgg(aobj: SMGAggObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]]

  /**
    * Get all indexes which would match this object view
    * @param ov
    * @return
    */
  def objectIndexes(ov: SMGObjectView): Seq[SMGIndex]

  /**
    * Get all indexes which would match any of the listed object views
    * @param ovs
    * @return
    */
  def objectsIndexes(ovs: Seq[SMGObjectView]): Seq[SMGIndex]

  /**
    * Get a read-only snapshot from the current map of
    * command (pre-fetch or rrd object) id and execution times (in milliseconds)
    * Useful to find slow commands
    *
    * @return
    */
  def commandExecutionTimes: Map[String, Long]

  //convenience ref ... TODO make this a def and read from config?
  val detailPeriods: List[String] = GrapherApi.detailPeriods

  // convenience reference to the remotesApi
  def remotes: SMGRemotesApi

  def searchCache: SMGSearchCache


  /**
    * Sort a sequence of image views by first grouping them by vars and then within each group - sort
    * by the descending average value (for the period) of the variable with index specified by sortBy
    * @param lst - sequence to sort
    * @param sortBy - 0-based index of the variable by which value to sort. Sort order is undefined if sortBy >= number of vars
    * @param period - period for which to calculate averages for sorting
    * @return - a sequence of SMGImageViewsGroup each representing a group of graphs with identical var definitions
    *         where within each group the images are sorted as described.
    */
  def xsortImageViews(lst: Seq[SMGImageView], sortBy: Int,
                      groupBy: SMGAggGroupBy.Value, period: String): Seq[SMGImageViewsGroup]


  def groupImageViews(lst: Seq[SMGImageView], groupBy: SMGAggGroupBy.Value): Seq[SMGImageViewsGroup]

  // TODO
  def groupImageViewsGroupsByRemote(dglst: Seq[SMGImageViewsGroup], xRemoteAgg: Boolean): Seq[SMGImageViewsGroup]

}

/**
  * Helper definining some constants
  */
object GrapherApi {
  val detailPeriods = List("24h", "3d", "1w", "14d", "1m", "1y")

  val defaultPeriod = detailPeriods.head
}
