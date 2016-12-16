package com.smule.smg

import scala.collection.mutable.ListBuffer

/**
  * Created by asen on 11/15/15.
  */

/**
  * An object representing the local SMG configuration as provided in yml file(s)
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
                           urlPrefix: String,
                           intervals: Set[Int],
                           preFetches: Map[String, SMGPreFetchCmd],
                           remotes: Seq[SMGRemote],
                           remoteMasters: Seq[SMGRemote],
                           pluginObjects: Map[String, Seq[SMGObjectView]],
                           objectAlertConfs: Map[String, SMGMonObjAlertConf],
                           notifyCommands: Map[String,SMGMonNotifyCmd],
                           objectNotifyConfs: Map[String, SMGMonObjNotifyConf],
                           hiddenIndexes: Map[String, SMGConfIndex]
                    ) extends SMGConfig {


  val rrdObjects: Seq[SMGRrdObject] = confViewObjects.filter(_.isInstanceOf[SMGRrdObject]).map(_.asInstanceOf[SMGRrdObject])

  override val viewObjects: Seq[SMGObjectView] = confViewObjects ++ pluginObjects.flatMap(t => t._2)

  override val viewObjectsById: Map[String, SMGObjectView] = viewObjects.groupBy(o => o.id).map( t => (t._1, t._2.head) )

  val viewObjectsByUpdateId: Map[String, Seq[SMGObjectView]] = viewObjects.groupBy(o => o.refObj.map(_.id).getOrElse(o.id))

  private val pluginUpdateObjects = pluginObjects.flatMap(t => t._2).filter(ov => ov.refObj.isDefined || ov.isInstanceOf[SMGObjectUpdate]).map {
    case update: SMGObjectUpdate => update
    case ov => ov.refObj.get
  }.groupBy(_.id).map { case (id, seq) => seq.head }

  val updateObjects: Seq[SMGObjectUpdate] = rrdObjects ++ pluginUpdateObjects


  val updateObjectsById: Map[String, SMGObjectUpdate] = updateObjects.groupBy(_.id).map(t => (t._1,t._2.head))

  def allRemotes: List[SMGRemote] = SMGRemote.local :: remotes.toList

  private def globalNotifyConf(key: String) =  globals.get(key).map { s =>
    s.split(",").map(cmdid => notifyCommands.get(cmdid)).filter(_.isDefined).map(_.get).toSeq
  }.getOrElse(Seq())


  val globalCritNotifyConf: Seq[SMGMonNotifyCmd] = globalNotifyConf("$notify-crit")
  val globalWarnNotifyConf: Seq[SMGMonNotifyCmd] = globalNotifyConf("$notify-warn")
  val globalSpikeNotifyConf: Seq[SMGMonNotifyCmd] = globalNotifyConf("$notify-spike")

  val globalNotifyBackoff: Int = globals.get("$notify-backoff").flatMap{ s =>
    SMGRrd.parsePeriod(s)
  }.getOrElse(SMGMonVarNotifyConf.DEFAULT_NOTIFY_BACKOFF)

  val notifyBaseUrl: String = globals.getOrElse("$notify-baseurl", "http://localhost:9000")
  val notifyRemoteId: Option[String] = globals.get("$notify-remote")

  val proxyDisable: Boolean = globals.getOrElse("$proxy-disable","false") == "true"
  val proxyTimeout: Long = globals.getOrElse("$proxy-timeout","30000").toLong

  val indexTreeLevels: Int = globals.getOrElse("$index-tree-levels", "1").toInt


  val MAX_RUNTREE_LEVELS = 10

  val runTreeLevelsDisplay: Int = globals.getOrElse("$run-tree-levels-display", "1").toInt

  // option to notify slaves on reload conf, this may be removed in the future
  val reloadSlaveRemotes: Boolean = globals.getOrElse("$reload-slave-remotes", "false") == "true"


  private def buildCommandsTree(interval: Int, rrdObjs: Seq[SMGRrdObject]): Seq[SMGFetchCommandTree] = {
    val ret = ListBuffer[SMGFetchCommandTree]()
    var recLevel = 0

    def buildTree(leafs: Seq[SMGFetchCommandTree]): Unit = {
      //      println(leafs)
      val byPf = leafs.groupBy(_.node.preFetch.getOrElse(""))
      val myParents = ListBuffer[SMGFetchCommandTree]()
      byPf.foreach { t =>
        val pfId = t._1
        val chldrn = t._2
        if (pfId == "") {
          // top level
          ret ++= chldrn
        } else {
          val pf = preFetches.get(pfId)
          if (pf.isEmpty) {
            SMGLogger.error(s"SMGLocalConfig.fetchCommandsTree: non existing pre-fetch id: $pfId")
            ret ++= chldrn
          } else {
            myParents += SMGFetchCommandTree(pf.get, chldrn)
          }
        }
      }
      if (myParents.nonEmpty) {
        recLevel += 1
        if (recLevel > MAX_RUNTREE_LEVELS) {
          throw new RuntimeException(s"SMGLocalConfig.fetchCommandsTree: Configuration error - recursion ($recLevel) exceeded $MAX_RUNTREE_LEVELS")
        }
        buildTree(myParents.toList.sortBy(_.node.id))
        recLevel -= 1
      }
    }
    buildTree(rrdObjs.sortBy(_.id).map(o => SMGFetchCommandTree(o, Seq())))
    // consolidate top-level trees sharing the same root
    val topLevelById = ret.toList.groupBy(_.node.id)
    topLevelById.keys.toList.sorted.map { cid =>
      val trees = topLevelById(cid)
      if (trees.tail.isEmpty) {
        trees.head
      } else {
        SMGFetchCommandTree(trees.head.node, trees.flatMap(_.children).sortBy(_.node.id))
      }
    }
  }

  // build and keep the commands trees (a sequence of trees for each interval)
  private val fetchCommandTrees: Map[Int, Seq[SMGFetchCommandTree]] = rrdObjects.groupBy(_.interval).map { t =>
    (t._1, buildCommandsTree(t._1, t._2))
  }


  /**
    * Get the top-level command trees for given interval
    * @param interval - interval for which we want the run trees
    * @return
    */
  def fetchCommandsTree(interval: Int): Seq[SMGFetchCommandTree] = {
    fetchCommandTrees.getOrElse(interval, Seq())
  }

  /**
    * Get all fetch command trees (can be one per interval) having the provided root id
    * if root is None - return all command trees
    * @param root
    * @return
    */
  def fetchCommandTreesWithRoot(root: Option[String]): Map[Int, Seq[SMGFetchCommandTree]] = {
    if (root.isDefined) {
      fetchCommandTrees.map { t =>
        (t._1, t._2.map(t => t.findTree(root.get)).filter(_.isDefined).map(_.get))
      }.filter(_._2.nonEmpty)
    } else fetchCommandTrees
  }

  /**
    * Get all rrd objects which depend (at some level) on the given cmdId. If interval is provided
    * only objects having the specified interval will be returned.
    * @param cmdId
    * @param intervalOpt
    * @return
    */
  def fetchCommandRrdObjects(cmdId: String, intervalOpt: Option[Int] = None) : Seq[SMGRrdObject] = {
    val ret = ListBuffer[SMGRrdObject]()
    val intvls = if (intervalOpt.isDefined) Seq(intervalOpt.get) else intervals.toSeq.sorted
    intvls.foreach { intvl =>
      val topLevel = fetchCommandTrees.getOrElse(intvl, Seq())
      val root = SMGFetchCommandTree.findTreeWithRoot(cmdId, topLevel)
      if (root.isDefined)
        ret ++= root.get.leafNodes.map(_.asInstanceOf[SMGRrdObject])
    }
    ret.toList
  }
}
