package com.smule.smg

import scala.concurrent.Future

/**
 * Created by asen on 11/10/15.
 */

/**
  * Class encapsulating graph options. All params are optional.
  * @param step - step/resolution to use in the graphs (default - rrdtool default for the period)
  * @param pl - period length - to limit the end period of graphs
  * @param xsort - whether to apply x-sort (by avg val) to the objects
  * @param disablePop - disable period-over-period dashed line
  * @param disable95pRule - disable 95%-ile ruler (line)
  * @param maxY - limit the Y-axis of the graph to that value (higher values not displayed)
  */
case class GraphOptions(step: Option[Int] = None,
                        pl: Option[String] = None,
                        xsort: Option[Int] = None,
                        disablePop: Boolean = false,
                        disable95pRule: Boolean = false,
                        maxY: Option[Double] = None ) {

  def fnSuffix(period:String) = {
    val goptsSx = (if (disablePop) "-dpp" else "") + (if (disable95pRule) "-d95p" else "") +
      (if (maxY.isDefined) s"-my${maxY.get}" else "")
    "-" + SMGRrd.safePeriod(period) + pl.map("-pl" + SMGRrd.safePeriod(_)).getOrElse("") + step.map("-" + _).getOrElse("") + goptsSx
  }
}

/**
  * The SMGrapher public API
  */
trait GrapherApi {

  //convenience ref
  val detailPeriods: List[String] = GrapherApi.detailPeriods

  /**
    * Execute a fetch + update run for given interval (to be called regularly by scheduler)
    * @param interval - interval in seconds
    */
  def run(interval:Int):Unit

  def runCommandsTree(interval: Int, cmdId: String): Boolean

  /**
    * Get Top Level configured indexes, grouped by remote
    * @return - sequence of tuples containing the remote id and a sequence of idnexes
    */
  def getTopLevelIndexesByRemote(rmt: Option[String]): Seq[(SMGRemote, Seq[SMGIndex])]


  /**
    * Get Top Level automatically discovered (by id) indexes
    * @return - sequence of indexes
    */
  def getAutoIndex: SMGAutoIndex

  /**
    * Get a configured index from its id
    * @param id - index id to lookup
    * @return - some [[SMGIndex]] if found, None otherwise
    */
  def getIndexById(id: String): Option[SMGIndex]

  /**
    * Get a [[SMGObjectView]] from its object id
    * @param id - id to lookup
    * @return - Some [[SMGObjectView]] if found, None otherwise
    */
  def getObjectView(id:String): Option[SMGObjectView]

  /**
    * Get a sequence of [[SMGObjectView]]s (each representing an object from which a graph image can be produced)
    * from given filter
    * @param filter - filter to use
    * @return - sequence of matching objects
    */
  def getFilteredObjects(filter: SMGFilter): Seq[SMGObjectView]


  /**
    * Asynchronous call to graph a [[SMGObjectView]] for a given sequence of periods.
    * @param obj - SMGRrdObject to graph
    * @param periods - sequence of strings each representing a period to graph
    * @return - future sequence of SMG image objects
    */
  def graphObject(obj:SMGObjectView, periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]]

  /**
    * Asynchronous call to graph multiple [[SMGObjectView]]s
    * for a given sequence of periods.
    * @param lst - list of SMGRrdObject to graph
    * @param periods - sequence of strings each representing a period to graph
    * @return - future sequence of SMG image objects
    */
  def graphObjects(lst: Seq[SMGObjectView], periods: Seq[String], gopts: GraphOptions): Future[Seq[SMGImageView]]

  /**
    * Asynchronous call to graph a [[SMGAggObject]] (representing an aggregate view from multiple [[SMGObjectView]]s,
    * each representing single rrd database), for a given sequence of periods.
    * @param aobj - an aggregate object to graph
    * @param periods - list of periods to graph for
    * @param xRemote - whether to download remote rrds and aggregate cross-remote
    * @return - future sequence of SMG image objects
    */
  def graphAggObject(aobj:SMGAggObject, periods: Seq[String], gopts: GraphOptions, xRemote: Boolean): Future[Seq[SMGImageView]]

  /**
    * Asynchronous call to graph a [[SMGObjectView]] for the set of
    * pre-defined default periods.
    * @param obj - [[SMGObjectView]] to graph
    * @return - future sequence of SMG image objects
    */
  def getObjectDetailGraphs(obj:SMGObjectView, gopts: GraphOptions): Future[Seq[SMGImageView]]

  /**
    * TODO
    * @param obj
    * @param params [[SMGRrdFetchParams]]
    * @return
    */
  def fetch(obj: SMGObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]]

  /**
    * TODO
    * @param aobj
    * @param params [[SMGRrdFetchParams]]
    * @return
    */
  def fetchAgg(aobj: SMGAggObjectView, params: SMGRrdFetchParams): Future[Seq[SMGRrdRow]]

  /**
    * Get all indexes which would match this object view
    * @param ov
    * @return
    */
  def objectIndexes(ov: SMGObjectView): Seq[SMGIndex]

  // convenience reference to the remotesApi
  def remotes: SMGRemotesApi

  def searchCache: SMGSearchCache
}

/**
  * Helper definining some constants
  */
object GrapherApi {
  val detailPeriods = List("24h", "3d", "1w", "14d", "1m", "1y")

  val defaultPeriod = detailPeriods.head
}
