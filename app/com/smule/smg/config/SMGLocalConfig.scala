package com.smule.smg.config

import java.util.concurrent.TimeUnit
import akka.util.Timeout
import com.smule.smg.cdash.CDashboardConfig
import com.smule.smg.core._
import com.smule.smg.monitor.SMGMonAlertConfObj
import com.smule.smg.notify.{SMGMonNotifyCmd, SMGMonNotifyConf, SMGMonNotifyConfObj}
import com.smule.smg.remote.SMGRemote
import com.smule.smg.rrd.{SMGRraDef, SMGRrd, SMGRrdConfig}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

object SMGLocalConfig {
  val DEFAULT_RRD_DIR: String = "smgrrd"
}

/**
  * An object representing the local SMG configuration as provided in yml file(s)
 *
  * @param globals - a Map of global (String) variables (name -> value)
  * @param indexes - a sequence of configured SMGIndex objects, each representing a filter to select view objects to
  *                display and/or a sequence of child indexes
  * @param rrdConf - rrd tool configuration object
  * @param imgDir - directory where to output graph images. should be accessible via HTTP
  * @param urlPrefix - url prefix to use when retruning image URLs
  * @param intervals - a set of intervals defined in the configuration
  * @param preFetches - map of all pre_fetch command objects by id
  * @param remotes - a set of remote instances configured in this config
  * @param pluginObjects - map of all object (views) defined in plugins, by plugin id
  * @param objectAlertConfs - map holding all configured alert thresholds by object (update) id
  * @param hiddenIndexes - map holding all "hidden" indexes, by their ID. These are not displayed as indexes
  *                      but useful for defining alert configurations.
  */
case class SMGLocalConfig(
                           globals: Map[String,String],
                           confViewObjects: Seq[SMGObjectView],
                           indexes: Seq[SMGConfIndex],
                           rrdConf: SMGRrdConfig,
                           imgDir: String,
                           defaultRrdDir: String,
                           rrdDirLevelsDef: Option[DirLevelsDef],
                           urlPrefix: String,
                           intervalConfs: Map[Int, IntervalThreadsConfig],
                           preFetches: Map[String, SMGPreFetchCmd],
                           remotes: Seq[SMGRemote],
                           remoteMasters: Seq[SMGRemote],
                           pluginObjects: Map[String, Seq[SMGObjectView]],
                           pluginPreFetches: Map[String, Map[String, SMGPreFetchCmd]],
                           objectAlertConfs: Map[String, SMGMonAlertConfObj],
                           notifyCommands: Map[String,SMGMonNotifyCmd],
                           objectNotifyConfs: Map[String, SMGMonNotifyConfObj],
                           hiddenIndexes: Map[String, SMGConfIndex],
                           customDashboards: Seq[CDashboardConfig],
                           rraDefs: Map[String, SMGRraDef],
                           private val authUsers: Seq[AuthUserConfig],
                           private val configErrors: List[String] // use allErrors at the bottom instead
                    ) extends SMGConfig {

  val authUsersByType: Map[String, Seq[AuthUserConfig]] = authUsers.groupBy(_.userType)

  private val (myUsersConf, userErrors) = SMGUsersConfig.fromConfigGlobalsAndAUthUsers(globals, authUsersByType)

  val usersConfig: SMGUsersConfig = myUsersConf

  val intervals: Set[Int] = intervalConfs.keySet

  private val validationErrors = ListBuffer[String]()

  validationErrors ++= userErrors

  private val log = SMGLogger

  private def processValidationError(msg: String, isWarn: Boolean = false) = {
    val marker = if (isWarn) "CONFIG_WARNING" else "CONFIG_ERROR"
    val mymsg = s"$marker: (validation): $msg"
    val logmsg = s"SMGLocalConfig.apply: $mymsg"
    if (isWarn)
      log.warn(logmsg)
    else
      log.error(logmsg)
    validationErrors += mymsg
  }

  private val rrdObjects: Seq[SMGRrdObject] = confViewObjects.filter(_.isInstanceOf[SMGRrdObject]).
    map(_.asInstanceOf[SMGRrdObject])

  lazy val rrdObjectsSize: Int = rrdObjects.size

  private val rrdAggObjects: Seq[SMGRrdAggObject] = confViewObjects.filter(_.isInstanceOf[SMGRrdAggObject]).
    map(_.asInstanceOf[SMGRrdAggObject])

  lazy val rrdAggObjectsSize: Int = rrdAggObjects.size

  val rrdAggObjectsByInterval: Map[Int, Seq[SMGRrdAggObject]] = rrdAggObjects.groupBy(_.interval)

  override val viewObjects: Seq[SMGObjectView] = confViewObjects ++ pluginObjects.flatMap(t => t._2)

  override val viewObjectsById: Map[String, SMGObjectView] = viewObjects.groupBy(o => o.id).map( t => (t._1, t._2.head) )

  val viewObjectsByUpdateId: Map[String, Seq[SMGObjectView]] = viewObjects.groupBy(o => o.refObj.map(_.id).getOrElse(o.id))

  private def getUpdateObjectsFromViewObjects(ovs: Seq[SMGObjectView]): Seq[SMGObjectUpdate] =
    ovs.filter(ov => ov.refObj.isDefined || ov.isInstanceOf[SMGObjectUpdate]).map {
      case update: SMGObjectUpdate => update
      case ov => ov.refObj.get
    }.distinct

  private val pluginsUpdateObjects = pluginObjects.flatMap(t => getUpdateObjectsFromViewObjects(t._2))

  lazy val pluginsUpdateObjectsSize: Int = pluginsUpdateObjects.size

  val updateObjects: Seq[SMGObjectUpdate] = rrdObjects ++ rrdAggObjects ++ pluginsUpdateObjects

  val updateObjectsById: Map[String, SMGObjectUpdate] = updateObjects.groupBy(_.id).map(t => (t._1,t._2.head))

  val commandObjects: Seq[SMGObjectUpdate] = updateObjects.
    filter(_.isInstanceOf[SMGFetchCommand])

  def allRemotes: List[SMGRemote] = SMGRemote.local :: remotes.toList

  private def globalNotifyConf(key: String) =  globals.get(key).map { s =>
    s.split("\\s*,\\s*").map(cmdid => notifyCommands.get(cmdid)).filter(_.isDefined).map(_.get).toSeq
  }.getOrElse(Seq())


  val globalSmgerrNotifyConf: Seq[SMGMonNotifyCmd] = globalNotifyConf("$notify-global")
  val globalCritNotifyConf: Seq[SMGMonNotifyCmd] = globalNotifyConf("$notify-crit")
  val globalFailNotifyConf: Seq[SMGMonNotifyCmd] = globalNotifyConf("$notify-fail") ++
    globalNotifyConf("$notify-unkn") // deprecated in favor of notify-fail
  val globalWarnNotifyConf: Seq[SMGMonNotifyCmd] = globalNotifyConf("$notify-warn")
  val globalAnomNotifyConf: Seq[SMGMonNotifyCmd] = globalNotifyConf("$notify-anom") ++
    globalNotifyConf("$notify-spike") // deprecated in favor of notify-anom

  val globalNanAsAnomaly: Boolean = globals.getOrElse("$monitor-nan-anomaly", "false") == "true"
  val globalMonitorProblemsMinSeverity: String = globals.getOrElse("$monitor-poroblems-severity", "WARNING")

  val globalNotifyBackoff: Int = globals.get("$notify-backoff").flatMap{ s =>
    SMGRrd.parsePeriod(s)
  }.getOrElse(SMGMonNotifyConf.DEFAULT_NOTIFY_BACKOFF)

  val globalNotifyStrikes: Int = globals.get("$notify-strikes").map { s =>
    Math.max(s.toInt,1)
  }.getOrElse(SMGMonNotifyConf.DEFAULT_NOTIFY_STRIKES)


  val notifyBaseUrl: String = globals.getOrElse("$notify-baseurl", "http://localhost:9000")
  val notifyRemoteId: Option[String] = globals.get("$notify-remote")

  val proxyDisable: Boolean = globals.getOrElse("$proxy-disable","false") == "true"
  val proxyTimeout: Long = globals.getOrElse("$proxy-timeout","30000").toLong

  val proxyTimeoutDuration: FiniteDuration = new Timeout(proxyTimeout, TimeUnit.MILLISECONDS).duration

  val indexTreeLevels: Int = globals.getOrElse("$index-tree-levels", "1").toInt

  val dashDefaultCols: Int = globals.getOrElse("$dash-default-cols", "6").toInt
  val dashDefaultRows: Int = globals.getOrElse("$dash-default-rows", "10").toInt

  val searchCacheMaxLevels: Int = globals.getOrElse("$search-max-levels", "2").toInt

  val defaultAutoRefreshEnabled: Boolean = globals.getOrElse("$default-auto-refresh", "false") == "true"
  val autoRefreshInterval: Int = globals.getOrElse("$auto-refresh-interval", "300").toInt

  // This needs to be set to around 2000 for IE support, and can be raised to 32k if one does not care about android
  // if the dash/filter form URL is estimated to exceed this size, a POST will be used instead of GET
  // Only draw back is that the resulting URLs will not be shareable
  val maxUrlSize: Int = globals.getOrElse("$max-url-size", "8000").toInt

  val remoteGraphTimeoutMs: Long = globals.getOrElse("$remote-graph-timeout-ms", "30000").toLong
  val remoteConfigFetchTimeoutMs: Long = globals.getOrElse("$remote-config-fetch-timeout-ms", "300000").toLong

  val MAX_RUNTREE_LEVELS = 10

  val runTreeLevelsDisplay: Int = globals.getOrElse("$run-tree-levels-display", "1").toInt

  // option to notify slaves on reload conf, this may be removed in the future
  val reloadSlaveRemotes: Boolean = globals.getOrElse("$reload-slave-remotes", "false") == "true"

  val monStateDir: String = globals.getOrElse("$monstate_dir", "monstate")

  private val allPreFetchesMap: Map[String, SMGPreFetchCmd] = {
    val allMaps = pluginPreFetches.values ++ Seq(preFetches).toSeq
    allMaps.foldLeft(Map[String,SMGPreFetchCmd]()) { case (itm, mm) =>
      itm ++ mm
    }
  }

  override val allPreFetches: Seq[SMGFetchCommand] = allPreFetchesMap.values.toSeq

  // build and keep the commands trees (a sequence of trees for each interval)
  private val fetchCommandTrees: Map[Int, Seq[SMGTree[SMGFetchCommand]]] = rrdObjects.groupBy(_.interval).map { t =>
    (t._1, SMGTree.buildTrees[SMGFetchCommand](t._2, allPreFetchesMap))
  }

  private def fetchCommandTreesSeqToMap[T <: SMGTreeNode](seq: Seq[SMGTree[T]]): Map[String, SMGTree[T]] = {
    var ret = Map[String, SMGTree[T]]()
    seq.foreach { t =>
      ret ++= t.id2TreeMap
    }
    ret
  }

  private val fetchCommandTreesById: Map[Int, Map[String, SMGTree[SMGFetchCommand]]] =
    fetchCommandTrees.map { case (intvl, seq) => (intvl, fetchCommandTreesSeqToMap(seq)) }


  val allCommandsById: Map[String, SMGFetchCommand] = allPreFetchesMap ++ commandObjects.map(_.asInstanceOf[SMGFetchCommand]).
    groupBy(_.id).map(t => (t._1, t._2.head))

  /**
    * Get the top-level command trees for given interval
    * @param interval - interval for which we want the run trees
    * @return
    */
  def getFetchCommandsTrees(interval: Int): Seq[SMGTree[SMGFetchCommand]] = {
    fetchCommandTrees.getOrElse(interval, Seq())
  }

  def getFetchCommandsTreesByInterval: Map[Int,Seq[SMGTree[SMGFetchCommand]]] = {
    intervals.map(i => (i, getFetchCommandsTrees(i))).filter(_._2.nonEmpty).toMap
  }

  /**
    * Get all fetch command trees (can be one per interval) having the provided root id
    * if root is None - return all command trees
    * @param root
    * @return
    */
  def getFetchCommandTreesWithRoot(root: Option[String]): Map[Int, Seq[SMGTree[SMGFetchCommand]]] = {
    if (root.isDefined) {
      fetchCommandTreesById.map { case (intvl, treeMap) =>
        (intvl, Seq(treeMap.get(root.get)).flatten)
      }.filter(_._2.nonEmpty)
    } else fetchCommandTrees
  }

  def getPreFetchCommandById(pfId: String): Option[SMGPreFetchCmd] = {
    var ret = preFetches.get(pfId)
    if (ret.isDefined) return ret
    pluginPreFetches.values.find(_.contains(pfId)).map(_(pfId))
  }

  /**
    * Get all rrd objects which depend (at some level) on the given cmdId. If interval is provided
    * only objects having the specified interval will be returned.
    * @param cmdId
    * @param forIntervals
    * @return
    */
  def getFetchCommandRrdObjects(cmdId: String, forIntervals: Seq[Int] = Seq()) : Seq[SMGRrdObject] = {
    val ret = ListBuffer[SMGRrdObject]()
    val intvls = if (forIntervals.nonEmpty) forIntervals else intervals.toSeq.sorted
    intvls.foreach { intvl =>
      val topLevelMap = fetchCommandTreesById.getOrElse(intvl, Map())
      val root = topLevelMap.get(cmdId) // SMGTree.findTreeWithRoot(cmdId, topLevel)
      if (root.isDefined)
        ret ++= root.get.leafNodes.map(_.asInstanceOf[SMGRrdObject])
    }
    ret.toList
  }

  // build and keep the plugin commands trees (a sequence of trees for each plugin)
  private val pluginCommandTrees: Map[String, Seq[SMGTree[SMGTreeNode]]] = pluginObjects.map { t =>
    val pluginId = t._1
    val objs = t._2
    val pfsMap = pluginPreFetches.getOrElse(pluginId, Map())
    (pluginId, SMGTree.buildTrees[SMGTreeNode](getUpdateObjectsFromViewObjects(objs), pfsMap))
  }

  private val pluginCommandTreesById: Map[String, Map[String,SMGTree[SMGTreeNode]]] =
    pluginCommandTrees.map { case (pluginId, seq) => (pluginId, fetchCommandTreesSeqToMap(seq)) }

  /**
    * Get the plugin update objects depending at some level on this command id
    * (as defined by plugin objects and prefetches)
    * @param pluginId
    * @param cmdId
    * @return
    */
  def getPluginFetchCommandUpdateObjects(pluginId: String, cmdId: String) : Seq[SMGObjectUpdate] = {
    val ret = ListBuffer[SMGObjectUpdate]()
    val topLevelMap = pluginCommandTreesById.getOrElse(pluginId, Map())
    val root = topLevelMap.get(cmdId) //SMGTree.findTreeWithRoot(cmdId, topLevel)
    if (root.isDefined)
        ret ++= root.get.leafNodes.map(_.asInstanceOf[SMGObjectUpdate])
    ret.toList
  }

  /**
    * Lookup the given pfId in preFetches or pluginPreFetches
    * @param pfId - id to lookup
    * @return - if found - Some tuple of the SMGPreFetchCmd and the (optional) pluginId for which the id was found
    */
  def findPreFetchCmd(pfId: String): Option[(SMGPreFetchCmd, Option[String])] = {
    if (preFetches.contains(pfId))
      Some((preFetches(pfId), None))
    else
      pluginPreFetches.find { case (plid, pfs) =>
        pfs.contains(pfId)
      }.map { case (plid, pfs) =>
        (pfs(pfId), Some(plid))
      }
  }

  // validate pre_fetch commands - specifying invalid command id would be ignored
  rrdObjects.foreach{ obj =>
    if (obj.preFetch.nonEmpty && !allPreFetchesMap.contains(obj.preFetch.get)) {
      processValidationError(s"object specifies non existing pre_fetch id: ${obj.id} - ${obj.preFetch.get}")
    }
  }

  val allErrors: List[String] = configErrors ++ validationErrors.toList

  val alertCondsSummary: SMGConfigAlertCondsSummary = SMGConfigAlertCondsSummary.build(this)
  val notifyCmdsSummary: SMGConfigNotifyConfsSummary = SMGConfigNotifyConfsSummary.build( this)

  def humanDesc: String = {
    val objus = updateObjects.size
    val rrdObjs = rrdObjects.size
    val rrdAggObjs = rrdAggObjects.size
    val othObjs = objus - (rrdObjs + rrdAggObjs)
    val intvls = intervalConfs.toList.sortBy(_._1).map(_._2.inspect).mkString(",")
    s"intervals=$intvls localUpdateObjects=$objus (rrd=$rrdObjs, " +
      s"rrdAgg=$rrdAggObjs, plugins=$othObjs) " +
      s"localViewObjects=${viewObjects.size} remotes=${remotes.size} auth: ${usersConfig.humanDesc}"
  }
}
