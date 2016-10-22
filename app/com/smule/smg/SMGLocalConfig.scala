package com.smule.smg

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

  val viewObjectsByUpdateId = viewObjects.groupBy(o => o.refObj.map(_.id).getOrElse(o.id))

  val updateObjects: Seq[SMGObjectUpdate] = rrdObjects ++
    pluginObjects.flatMap(t => t._2).filter(ov => ov.refObj.isDefined || ov.isInstanceOf[SMGObjectUpdate]).map {
      ov => ov match {
        case update: SMGObjectUpdate => update
        case _ => ov.refObj.get
      }
    }

  val updateObjectsById = updateObjects.groupBy(_.id).map(t => (t._1,t._2.head))

  def allRemotes = SMGRemote.local :: remotes.toList

  private def globalNotifyConf(key: String) =  globals.get(key).map { s =>
    s.split(",").map(cmdid => notifyCommands.get(cmdid)).filter(_.isDefined).map(_.get).toSeq
  }.getOrElse(Seq())


  val globalCritNotifyConf = globalNotifyConf("$notify-crit")
  val globalWarnNotifyConf = globalNotifyConf("$notify-warn")
  val globalSpikeNotifyConf = globalNotifyConf("$notify-spike")

  val globalNotifyBackoff = globals.get("$notify-backoff").flatMap{ s =>
    SMGRrd.parsePeriod(s)
  }.getOrElse(SMGMonVarNotifyConf.DEFAULT_NOTIFY_BACKOFF)

  val notifyBaseUrl = globals.getOrElse("$notify-baseurl", "http://localhost:9000")
  val notifyRemoteId = globals.get("$notify-remote")

  val proxyDisable = globals.getOrElse("$proxy-disable","false") == "true"
  val proxyTimeout = globals.getOrElse("$proxy-timeout","30000").toLong

  val indexTreeLevels = globals.getOrElse("$index-tree-levels", "1").toInt

  // option to notify slaves on reload conf, this may be removed in the future
  val reloadSlaveRemotes = globals.getOrElse("$reload-slave-remotes", "false") == "true"

}
