package com.smule.smg

import java.io.File
import java.nio.file.{FileSystems, PathMatcher}
import java.util
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}

import akka.actor.{ActorSystem, DeadLetter, Props}
import org.yaml.snakeyaml.Yaml
import play.api.Configuration

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

/**
  * Created by asen on 11/15/15.
  */

trait SMGConfigReloadListener {
  def reload(): Unit
}

/**
  * An interface for a service managing the local SMG config, to beinjected by Guice
  */
trait SMGConfigService {

  val defaultInterval: Int = 60 // seconds
  val defaultTimeout: Int = 30  // seconds

  /**
    * Get the current configuration as cached during startup or previous reload.
    *
    * @return - the current SMGLocalConfig object
    */
  def config: SMGLocalConfig

  /**
    * reload config.yml
    */
  def reload(): Unit

  /**
    * Whether SMG is using its internal Akka scheduler or external (e.g. cron-driven) scheduler.
    * This is specified in application.conf and requires restart to change
    */
  val useInternalScheduler: Boolean

  /**
  * Configured (in application.conf) plugins
  */
  val plugins: Seq[SMGPlugin]

  val pluginsById: Map[String, SMGPlugin]

  /**
  * register an object instance as "data feed listener", so that it gets notified on all monitor state events
  * SMGMonitor registers itself, but plugins can register too
  * @param lsnr - the instance to register
  */
  def registerDataFeedListener(lsnr: SMGDataFeedListener): Unit

  protected def dataFeedListeners: Seq[SMGDataFeedListener]

  /**
  * Send a data feed object message to all registered listeners for processing
  * @param msg - the message to send
  */
  def sendObjMsg(msg: SMGDFObjMsg): Unit = dataFeedListeners.foreach(dfl => Try(dfl.receiveObjMsg(msg)))

  /**
  * Send a data feed "Pre fetch" command message to all registered listeners for processing
  * @param msg - the message to send
  */
  def sendPfMsg(msg: SMGDFPfMsg): Unit = dataFeedListeners.foreach(dfl => Try(dfl.receivePfMsg(msg)))

  /**
  * Send a data feed "run" (e.g. finished/overlap etc) message to all registered listeners for processing
  * @param msg - the message to send
  */
  def sendRunMsg(msg: SMGDFRunMsg): Unit = dataFeedListeners.foreach(dfl => Try(dfl.receiveRunMsg(msg)))


  /**
  * Register an object instance to be notified on config reloads
  * @param lsnr - the object reference to register
  */
  def registerReloadListener(lsnr: SMGConfigReloadListener): Unit

  def notifyReloadListeners(ctx: String): Unit

  /**
  * Get all applicable to the provided object value (at index vix) AlertConfigs (a.k.a. checks)
  * @param ou
  * @param vix
  * @return
  */
  def objectValueAlertConfs(ou: SMGObjectUpdate, vix: Int): Seq[SMGMonVarAlertConf] = {
    val acs = config.objectAlertConfs
    if (acs.contains(ou.id))
      acs(ou.id).varConf(vix)
    else
      Seq()
  }


  /**
  * Get all applicable to the provided object value (at index vix) Notification configs
  * @param ou
  * @param vixOpt
  * @param atSeverity
  * @return a tuple of commands to execute together with a backoff time
  */
  def objectVarNotifyCmdsAndBackoff(ou: SMGObjectUpdate, vixOpt: Option[Int],
                                    atSeverity: SMGMonNotifySeverity.Value): (Seq[SMGMonNotifyCmd], Int) = {
    val oncOpt = config.objectNotifyConfs.get(ou.id)
    val isDisabledAndBackoffOpt = oncOpt.map(_.getIsDisabledAndBackoff(vixOpt))
    val retCmds = if (isDisabledAndBackoffOpt.exists(_._1)) {
      Seq() // there is a conf and it says disabled
    } else {
      val objConf = if (oncOpt.isDefined) {
        val vixes = if (vixOpt.isDefined) Seq(vixOpt.get) else ou.vars.indices
        vixes.flatMap { vix =>
          oncOpt.get.varConf(vix).flatMap { vnc =>
            atSeverity match {
              case SMGMonNotifySeverity.CRITICAL => vnc.crit
              case SMGMonNotifySeverity.UNKNOWN => vnc.unkn
              case SMGMonNotifySeverity.WARNING => vnc.warn
              case SMGMonNotifySeverity.ANOMALY => vnc.spike
              case _ => Seq()
            }
          }
        }.distinct.map(s => config.notifyCommands.get(s)).filter(_.isDefined).map(_.get)
      } else
        Seq()
      val globalConf = globalNotifyCmds(atSeverity)
      (globalConf ++ objConf).distinct
    }
    val notifBackoff = isDisabledAndBackoffOpt.flatMap(_._2).getOrElse(config.globalNotifyBackoff)
    (retCmds, notifBackoff)
  }

  /**
  * Get all "global" configured notficiations commands for the provided severity
  * @param atSeverity
  * @return
  */
  def globalNotifyCmds(atSeverity: SMGMonNotifySeverity.Value): Seq[SMGMonNotifyCmd] = {
    atSeverity match {
      case SMGMonNotifySeverity.SMGERR => config.globalSmgerrNotifyConf
      case SMGMonNotifySeverity.CRITICAL => config.globalCritNotifyConf
      case SMGMonNotifySeverity.UNKNOWN => config.globalUnknNotifyConf
      case SMGMonNotifySeverity.WARNING => config.globalWarnNotifyConf
      case SMGMonNotifySeverity.ANOMALY => config.globalSpikeNotifyConf
      case _ => Seq()
    }
  }

  def objectVarNotifyStrikes(ou: SMGObjectUpdate, vixOpt: Option[Int]): Int = {
    val oncOpt = config.objectNotifyConfs.get(ou.id)
    val vixes = if (vixOpt.isDefined) Seq(vixOpt.get) else ou.vars.indices
    val ret = vixes.map { vix =>
      oncOpt.map{ onc =>
        val seq = onc.varConf(vix)
        if (seq.isEmpty)
          config.globalNotifyStrikes
        else
          seq.map(_.notifyStrikes.getOrElse(config.globalNotifyStrikes)).min
      }.getOrElse(config.globalNotifyStrikes)
    }.min
    Math.max(ret,1)
  }
}

/**
  * A singleton (injected by Guice) responsible for parsing and caching local SMG configuration
  *
  * @param configuration - Play configuration object to bootstrap our config from
  */
@Singleton
class SMGConfigServiceImpl @Inject() (configuration: Configuration, actorSystem: ActorSystem) extends SMGConfigService {
  private val log = SMGLogger

  /**
  * @inheritdoc
  */
  override val useInternalScheduler: Boolean = configuration.getBoolean("smg.useInternalScheduler").getOrElse(true)

  private val callSystemGcOnReload: Boolean = configuration.getBoolean("smg.callSystemGcOnReload").getOrElse(true)

  /**
    * XXX looks like java is leaking direct memory buffers (or possibly - just slowing
    * down external commands when reclaiming these on the fly) when reloading conf.
    * This is an attempt to fix that after realizing that a "manual gc" via jconsole clears overlap issues.
    * This method can be disabled via application.conf
    */
  private def callSystemGc(ctx: String): Unit = {
    // TODO synchronize ?
    if (callSystemGcOnReload) {
      // XXX looks like java is leaking direct memory buffers (or possibly - just slowing
      // down external commands when reclaiming these on the fly) when reloading conf.
      // This is an attempt to fix that after realizing that a "manual gc" via jconsole clears overlap issues
      log.info(s"ConfigService ($ctx) calling System.gc() ... START")
      System.gc()
      log.info(s"ConfigService ($ctx) calling System.gc() ... DONE")
    } else {
      log.info(s"ConfigService ($ctx) calling of System.gc() is disabled via smg.callSystemGcOnReload=false")
    }
  }

  /**
  * root Yaml config file to use
  */
  private val currentConfigFile: String = configuration.getString("smg.config").getOrElse("/etc/smg/config.yml")

  private val defaultThreadsPerInterval: Int = configuration.getInt("smg.defaultThreadsPerInterval").getOrElse(4)

  private val pluginsApplicationConf = configuration.getConfigList("smg.plugins") match {
    case Some(conf) => conf.map(v => SMGPluginConfig(
      v.getString("id").get,
      v.getString("class").get,
      v.getInt("interval").get,
      v.getString("config").get)).toSeq
    case None => Seq[SMGPluginConfig]()
  }

  private def createPlugins: Seq[SMGPlugin] = {
    pluginsApplicationConf.filter{ ac =>
      val ret = (ac.confFile != "") && new File(ac.confFile).exists()
      if (!ret) {
        log.warn("SMGConfigServiceImpl.createPlugins: Ignoring plugin application.conf entry specifying non-existing confFile: " + ac)
      }
      ret
    }.map { ac =>
      val klass = Class.forName(ac.className)
      klass.getConstructor(
        classOf[String],
        classOf[Int],
        classOf[String],
        classOf[SMGConfigService]
      ).newInstance(ac.id,
        ac.interval.asInstanceOf[Object],
        ac.confFile,
        this
      ).asInstanceOf[SMGPlugin]
    }
  }

  // Data feed listeners
  private val myDataFeedListeners = ListBuffer[SMGDataFeedListener]()

  private val dataFeedEnabled: Boolean =  configuration.getBoolean("smg.dataFeedEnabled").getOrElse(true)

  override def dataFeedListeners: List[SMGDataFeedListener] = if (dataFeedEnabled) myDataFeedListeners.toList else List()

  override def registerDataFeedListener(lsnr: SMGDataFeedListener):Unit = myDataFeedListeners.synchronized(myDataFeedListeners += lsnr)

  // Config reload listeners

  private val myConfigReloadListeners = ListBuffer[SMGConfigReloadListener]()
  override  def registerReloadListener(lsnr: SMGConfigReloadListener): Unit = {
    myConfigReloadListeners.synchronized(myConfigReloadListeners += lsnr)
  }
  def reloadListerenrs: List[SMGConfigReloadListener] = myConfigReloadListeners.synchronized(myConfigReloadListeners.toList)

  override def notifyReloadListeners(ctx: String): Unit = {
    val myrlsnrs = reloadListerenrs
    myrlsnrs.foreach { lsnr =>
      try {
        lsnr.reload()
      } catch {
        case t: Throwable => {
          log.ex(t, s"ConfigService.notifyReloadListeners($ctx): exception in reload from lsnr=$lsnr")
        }
      }
    }
    log.info(s"ConfigService.notifyReloadListeners($ctx) - notified ${myrlsnrs.size} listeners")
    callSystemGc(ctx)
  }

  /**
  * @inheritdoc
  */
  override val plugins: Seq[SMGPlugin] = createPlugins

  override val pluginsById: Map[String, SMGPlugin] = plugins.groupBy(_.pluginId).map(t => (t._1, t._2.head))

  private var currentConfig = getNewConfig

  /**
  * @inheritdoc
  */
  override def config: SMGLocalConfig = currentConfig

  private val reloadIsRunning: AtomicBoolean = new AtomicBoolean(false)

  /**
  * @inheritdoc
  */
  override def reload(): Unit = {
    if (!reloadIsRunning.getAndSet(true)) {
      val t0 = System.currentTimeMillis()
      log.debug("SMGConfigServiceImpl.reload: Starting at " + t0)
      try {
        val newConf = getNewConfig
        currentConfig.synchronized {
          currentConfig = newConf
        }
        val t1 = System.currentTimeMillis()
        log.info("SMGConfigServiceImpl.reloadConf: completed for " + (t1 - t0) + "ms. rrdConf=" + newConf.rrdConf +
                " imgDir=" + newConf.imgDir + " urlPrefix=" + newConf.urlPrefix +
                " objectsCount=" + newConf.rrdObjects.size +
                " intervals=" + newConf.intervals.toList.sorted.mkString(","))
        notifyReloadListeners("ConfigService.reload")
      } finally {
        reloadIsRunning.set(false)
      }
    } else {
      log.warn("SMGConfigServiceImpl.reload: Reload is already running in another thread, aborting")
    }
  }


  private def getListOfFiles(dir: String, matcher: PathMatcher):List[File] = {
    val d = new File(dir)
    if (d.exists && d.isDirectory) {
      d.listFiles.filter{ (f:File) =>
        //        log.info(f.toPath);
        f.isFile && matcher.matches(f.toPath)}.toList.sortBy(f => f.toPath)
    } else {
      log.warn("SMGConfigServiceImpl.getListOfFiles: CONFIG_WARNING: " + dir + " : glob did not match anything")
      List[File]()
    }
  }

  private def expandGlob(glob: String) : List[String] = {
    if (new File(glob).isFile) {
      return List(glob)
    }
    //    log.info("expandGlob: Expanding glob: " + glob)
    val fs = FileSystems.getDefault
    var dir = glob
    val sepIdx = glob.lastIndexOf(fs.getSeparator)
    if (sepIdx != -1) {
      dir = glob.substring(0, sepIdx)
    }
    //    log.info("expandGlob: listing dir " + dir + " with glob " + glob)
    val matcher = fs.getPathMatcher("glob:" + glob)
    getListOfFiles(dir, matcher).map( (f: File) => f.getPath)
  }

  private def keyValFromMap(m: java.util.Map[String, Object]): (String,Object) = {
    val firstKey = m.keys.collectFirst[String]{ case x => x }.getOrElse("")
    val retVal = m.remove(firstKey)
    if (retVal != null)
      (firstKey, retVal)
    else
      (firstKey, m)
  }

  /**
  * Generate a new immutable SMGLocalConfig object, using mutable helpers in the process
  *
  * @return - a newly parsed from currentConfigFile configuration
  */
  private def getNewConfig: SMGLocalConfig = {
    val globalConf = mutable.Map[String,String]()
    val allViewObjectsConf  = ListBuffer[SMGObjectView]()
    val objectIds = mutable.Map[String, SMGObjectView]()
    val objectUpdateIds = mutable.Map[String, SMGObjectUpdate]()
    val indexAlertConfs = ListBuffer[(SMGConfIndex, String, SMGMonVarAlertConf)]()
    val indexNotifyConfs = ListBuffer[(SMGConfIndex, String, SMGMonNotifyConf)]()
    var indexConfs = ListBuffer[SMGConfIndex]()
    val indexIds = mutable.Set[String]()
    val indexMap = mutable.Map[String,ListBuffer[String]]()
    val hiddenIndexConfs = mutable.Map[String, SMGConfIndex]()
    val objectAlertConfMaps = mutable.Map[String,mutable.Map[Int, ListBuffer[SMGMonVarAlertConf]]]()
    val objectNotifyConfMaps = mutable.Map[String,mutable.Map[Int, ListBuffer[SMGMonNotifyConf]]]()
    var rrdDir = "smgrrd"
    val rrdTool = "rrdtool"
    val imgDir = "public/smg"
    val urlPrefix: String = "/assets/smg"
    val intervals = mutable.Set[Int](plugins.map(_.interval).filter(_ != 0):_*)
    val preFetches = mutable.Map[String, SMGPreFetchCmd]()
    val notifyCommands = mutable.Map[String, SMGMonNotifyCmd]()
    val remotes = ListBuffer[SMGRemote]()
    val remoteMasters = ListBuffer[SMGRemote]()
    val rraDefs = mutable.Map[String, SMGRraDef]()

    def addAlertConf(oid: String, ix: Int, ac: SMGMonVarAlertConf) = {
      if (!objectAlertConfMaps.contains(oid)) objectAlertConfMaps(oid) = mutable.Map()
      if (!objectAlertConfMaps(oid).contains(ix)) objectAlertConfMaps(oid)(ix) = ListBuffer()
      objectAlertConfMaps(oid)(ix) += ac
    }

    def addNotifyConf(oid: String, ix: Int, nc: SMGMonNotifyConf) = {
      if (!objectNotifyConfMaps.contains(oid)) objectNotifyConfMaps(oid) = mutable.Map()
      if (!objectNotifyConfMaps(oid).contains(ix)) objectNotifyConfMaps(oid)(ix) = ListBuffer()
      objectNotifyConfMaps(oid)(ix) += nc
    }

    def processInclude(glob: String): Unit = {
      log.debug("SMGConfigServiceImpl.processInclude: " + glob)
      for (fn <- expandGlob(glob)) parseConf(fn)
    }

    def processPrefetch(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]]
      if (yamlMap.contains("id") && yamlMap.contains("command")) {
        val id = yamlMap.get("id").toString
        if (!validateOid(id)) {
          log.error("SMGConfigServiceImpl.processPrefetch(" + confFile + "): CONFIG_ERROR: id invalid or already defined (ignoring): " +
            id +":" + yamlMap.toString)
        } else {
          val cmd = SMGCmd(yamlMap.get("command").toString, yamlMap.getOrElse("timeout", 30).asInstanceOf[Int]) //  TODO get 30 from a val
          val parentPfStr = yamlMap.getOrElse("pre_fetch", "").toString
          val parentPf = if (parentPfStr == "") None else Some(parentPfStr)
          val ignoreTs = yamlMap.contains("ignorets") && (yamlMap.get("ignorets").toString != "false")
          val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, id, yamlMap.toMap.map(kv => (kv._1, kv._2.toString)))
          preFetches(id) = SMGPreFetchCmd(id, cmd, parentPf, ignoreTs, notifyConf)
        }
      } else {
        log.error("SMGConfigServiceImpl.processPrefetch(" + confFile + "): CONFIG_ERROR: $pre_fetch yamlMap does not have command and id: " + yamlMap.toString)
      }
    }

    def processNotifyCommand(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]]
      if (yamlMap.contains("id") && yamlMap.contains("command")) {
        val id = yamlMap.get("id").toString
        if (notifyCommands.contains(id)) {
          log.error("SMGConfigServiceImpl.processNotifyCommand(" + confFile + "): CONFIG_ERROR: id already defined (ignoring): " +
            id +":" + yamlMap.toString)
        } else {
          notifyCommands(id) = SMGMonNotifyCmd(id, yamlMap.get("command").toString, yamlMap.getOrElse("timeout", 30).asInstanceOf[Int])
        }
      } else {
        log.error("SMGConfigServiceImpl.processNotifyCommand(" + confFile + "): CONFIG_ERROR: $notify-command yamlMap does not have command and id: " + yamlMap.toString)
      }
    }

    def processRemote(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]]
      if (yamlMap.contains("id") && yamlMap.contains("url")) {
        if (yamlMap.contains("slave_id")) {
          remoteMasters += SMGRemote(yamlMap.get("id").toString, yamlMap.get("url").toString, Some(yamlMap.get("slave_id").toString))
        } else
          remotes += SMGRemote(yamlMap.get("id").toString, yamlMap.get("url").toString)
      } else {
        log.error("SMGConfigServiceImpl.processRemote(" + confFile + "): CONFIG_ERROR: $remote yamlMap does not have id and url: " + yamlMap.toString)
      }
    }

    def processRraDef(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]]
      if (yamlMap.contains("id") && yamlMap.contains("rra")) {
        val rid = yamlMap.get("id").toString
        if (rraDefs.contains(rid)) {
          log.error("SMGConfigServiceImpl.processRraDef(" + confFile + "): CONFIG_ERROR: duplicate $rra_def id: " + rid)
        } else {
          rraDefs(rid) = SMGRraDef(rid, yamlMap.get("rra").asInstanceOf[util.ArrayList[String]].toList)
          log.debug("SMGConfigServiceImpl.processRraDef: Added new rraDef: " + rraDefs(rid))
        }
      } else {
        log.error("SMGConfigServiceImpl.processRraDef(" + confFile + "): CONFIG_ERROR: $rra_def yamlMap does not have id and rra: " + yamlMap.toString)
      }
    }

    def processGlobal( t: (String,Object) , confFile: String ): Unit = {
      val key = t._1
      val sval = t._2.toString
      if (globalConf.contains(key)) {
        log.warn(s"SMGConfigServiceImpl.processGlobal($confFile): CONFIG_ERROR: Overwriting duplicate global " +
          s"value: key=$key oldval=${globalConf(key)} newval=$sval")
      }
      if (key == "$rrd_dir") {
        rrdDir = sval
        new File(rrdDir).mkdirs()
      }
      globalConf(key) = sval
    }

    def processIndex( t: (String,Object), isHidden: Boolean, confFile: String ): Unit = {
      val idxId = t._1.substring(1)
      if (indexIds.contains(idxId)) {
        log.error("SMGConfigServiceImpl.processIndex(" + confFile + "): CONFIG_ERROR: Skipping duplicate index with id: " + t._1)
      } else {
        try {
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]]
          val idx = new SMGConfIndex(idxId, ymap)
          indexIds += idxId
          if (isHidden) {
            if (hiddenIndexConfs.contains(idxId)) {
              log.error("SMGConfigServiceImpl.processIndex(" + confFile + "): CONFIG_ERROR: detected duplicate hidden index with id: " + t._1)
            }
            hiddenIndexConfs(idxId) = idx
          } else {
            indexConfs += idx
            if (idx.parentId.isDefined) {
              if (!indexMap.contains(idx.parentId.get)) {
                indexMap(idx.parentId.get) = ListBuffer()
              }
              indexMap(idx.parentId.get) += idx.id
            }
            if (idx.childIds.nonEmpty) {
              if (!indexMap.contains(idx.id)) {
                indexMap(idx.id) = ListBuffer()
              }
              for (c <- idx.childIds) indexMap(idx.id) += c
            }
          }
          // process alert/notify confs
          if (ymap.containsKey("alerts")){
            val alertsLst = ymap("alerts").asInstanceOf[java.util.ArrayList[java.util.Map[String, Object]]]
            alertsLst.foreach { m =>
              val sm = m.map(t => (t._1, t._2.toString)).toMap
              val src = if (isHidden) SMGMonAlertConfSource.HINDEX else SMGMonAlertConfSource.INDEX
              val ac = SMGMonVarAlertConf.fromVarMap(src, idx.id, sm)
              if (ac.isDefined) indexAlertConfs += Tuple3(idx, sm.getOrElse("label","ds" + idx), ac.get)
              val nc = SMGMonNotifyConf.fromVarMap(src, idx.id, sm)
              if (nc.isDefined) indexNotifyConfs += Tuple3(idx, sm.getOrElse("label","ds" + idx), nc.get)
            }
          }
        } catch {
          case x : ClassCastException => log.error("SMGConfigServiceImpl.processIndex(" + confFile +
            "): CONFIG_ERROR:: bad index tuple (" + t.toString + ") ex: " + x.toString)
        }
      }
    }

    def validateOid(oid: String): Boolean = oid.matches("^[\\w\\._-]+$") && 
      (!objectIds.contains(oid)) && (!preFetches.contains(oid))

    def processObject( t: (String,Object), confFile: String ): Unit = {
      val oid = t._1
      if (!validateOid(oid)){
        log.error("SMGConfigServiceImpl.processObject(" + confFile + "): CONFIG_ERROR: Skipping object with invalid or duplicate id: " + oid)
      } else {
        try {
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]]
          // check if we are defining a graph object vs rrd object (former instances reference the later)
          if (ymap.contains("ref")) { // a graph object
            val refid = ymap.get("ref").asInstanceOf[String]
            if (!objectUpdateIds.contains(refid)){
              log.error("SMGConfigServiceImpl.processObject(" + confFile +
                "): CONFIG_ERROR: Skipping graph object object with non existing update ref refid=" + refid+ " oid=" + oid)
            } else {
              val refobj = objectIds(refid)
              val refUpdateObj = objectUpdateIds.get(refid)
              val obj = SMGraphObject(oid, refobj.interval, refobj.vars,
                ymap.getOrElse("cdef_vars", new java.util.ArrayList[java.util.Map[String, Object]]() ).
                  asInstanceOf[java.util.ArrayList[java.util.Map[String, Object]]].toList.map(
                    (m: java.util.Map[String,Object]) => m.map { t => (t._1, t._2.toString) }.toMap
                  ),
                ymap.getOrElse("title", refobj.title).toString,
                ymap.getOrElse("stack", refobj.stack).asInstanceOf[Boolean],
                ymap.getOrElse("gv", new util.ArrayList[Int]()).asInstanceOf[util.ArrayList[Int]].toList,
                refobj.rrdFile, refUpdateObj, refobj.rrdType)
              objectIds(oid) = obj
              allViewObjectsConf += obj
            }
          } else { //no ref - plain rrd object
            val rraDef = if (ymap.contains("rra")) {
                val rid = ymap.get("rra").toString
                if (rraDefs.contains(rid)){
                  rraDefs.get(rid)
                } else {
                  log.error("SMGConfigServiceImpl.processObject(" + confFile +
                    "): CONFIG_ERROR: Ignoring non-existing rra value rra=" + rid + " oid=" + oid)
                  None
                }
              } else None

            val ymapVars = ymap("vars").asInstanceOf[java.util.ArrayList[java.util.Map[String, Object]]].toList.map(
              (m: java.util.Map[String,Object]) => m.map { t => (t._1, t._2.toString) }.toMap
            )
            // parse alert confs
            ymapVars.zipWithIndex.foreach { t =>
              val ix = t._2
              val m = t._1
              val ac = SMGMonVarAlertConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid, m)
              if (ac.isDefined) {
                addAlertConf(oid, ix, ac.get)
              }
            }
            // parse notify confs
            ymapVars.zipWithIndex.foreach { t =>
              val ix = t._2
              val m = t._1
              val nc = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid, m)
              if (nc.isDefined) {
                addNotifyConf(oid, ix, nc.get)
              }
            }
            // exclude alert- and notify- defs from the vars maps so it is not passed around remotes and does not mess up aggregation
            val ymapFilteredVars = ymapVars.map { m =>
              m.filter(t => !(SMGMonVarAlertConf.isAlertKey(t._1) || SMGMonNotifyConf.isNotifyKey(t._1)) )
            }
            // XXX support for both rrdType (deprecated) and rrd_type syntax
            val myRrdType = if (ymap.contains("rrd_type"))
                ymap("rrd_type").toString
              else ymap.getOrElse("rrdType", "GAUGE").toString
            val myDefaultInterval = globalConf.getOrElse("$default-interval", defaultInterval.toString).toInt
            val myDefaultTimeout = globalConf.getOrElse("$default-timeout", defaultTimeout.toString).toInt
            val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid, ymap.toMap.map(kv => (kv._1, kv._2.toString)))
            val obj = SMGRrdObject(
                id = oid,
                command = SMGCmd(ymap("command").toString, ymap.getOrElse("timeout", myDefaultTimeout).asInstanceOf[Int]),
                vars = ymapFilteredVars,
                title = ymap.getOrElse("title", oid).toString,
                rrdType = myRrdType,
                interval = ymap.getOrElse("interval", myDefaultInterval).asInstanceOf[Int],
                stack = ymap.getOrElse("stack", false).asInstanceOf[Boolean],
                preFetch = if (ymap.contains("pre_fetch")) Some(ymap.get("pre_fetch").toString) else None,
                rrdFile = Some(rrdDir + "/" + oid + ".rrd"),
                rraDef = rraDef,
                rrdInitSource = if (ymap.contains("rrd_init_source")) Some(ymap.get("rrd_init_source").toString) else None,
                notifyConf = notifyConf
              )
            objectIds(oid) = obj
            objectUpdateIds(oid) = obj
            allViewObjectsConf += obj
            intervals += obj.interval
          }
        } catch {
          case x : ClassCastException => log.error("SMGConfigServiceImpl.processObject(" + confFile +
            "): CONFIG_ERROR:: bad object tuple (" + t.toString + ") ex: " + x.toString)
        }
      }
    }

    def processAggObject( t: (String,Object), confFile: String ): Unit = {
      val oid =  t._1.substring(1)
      if (!validateOid(oid)){
        log.error("SMGConfigServiceImpl.processAggObject(" + confFile + "): CONFIG_ERROR: Skipping object with invalid or duplicate id: " + oid)
      } else {
        try {
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]]
          val confOp = ymap.getOrDefault("op", "SUM")
          val op = confOp match {
            case "SUMN" => "SUMN"
            case "AVG"  => "AVG"
            case "SUM" => "SUM"
            case _ => {
              log.warn(s"SMGConfigServiceImpl.processAggObject($confFile): CONFIG_WARNING: unsupported agg op for $oid, assuming SUM")
              "SUM"
            }
          }
          if (ymap.contains("ids")){
            val ids = ymap("ids").asInstanceOf[util.ArrayList[String]].toList
            val objOpts = ids.map { ovid =>
              val ret = objectIds.get(ovid).flatMap(_.refObj)
              if (ret.isEmpty) {
                log.error(s"SMGConfigServiceImpl.processAggObject($confFile): CONFIG_ERROR: agg object references " +
                  s"undefined id: $oid, ref id=$ovid (will be ignored)")
              }
              ret
            }
            if (objOpts.nonEmpty && objOpts.forall(_.isDefined)){
              val objs = objOpts.map(_.get)
              val rrdAggObj = SMGRrdAggObject(
                id = oid,
                ous = objs,
                aggOp = op,
                vars = objs.head.vars,
                title = ymap.getOrElse("title", oid).toString,
                rrdType = objs.head.rrdType,
                interval = objs.head.interval,
                stack = ymap.getOrElse("stack", false).asInstanceOf[Boolean],
                rrdFile = Some(rrdDir + "/" + oid + ".rrd"),
                rraDef = objs.head.rraDef,
                rrdInitSource = if (ymap.contains("rrd_init_source")) Some(ymap.get("rrd_init_source").toString) else None,
                notifyConf = None // TODO
              )
              objectIds(oid) = rrdAggObj
              objectUpdateIds(oid) = rrdAggObj
              allViewObjectsConf += rrdAggObj
            } // else - error already logged
          } else {
            log.warn(s"SMGConfigServiceImpl.processAggObject($confFile): CONFIG_ERROR: agg object definition without ids: $oid, ignoring")
          }
        } catch {
          case x : ClassCastException => log.error("SMGConfigServiceImpl.processAggObject(" + confFile +
            "): CONFIG_ERROR:: bad object tuple (" + t.toString + ") ex: " + x.toString)
        }
      }
    }

    def parseConf(confFile: String): Unit = {
      val t0 = System.currentTimeMillis()
      log.debug("SMGConfigServiceImpl.parseConf(" + confFile + "): Starting at " + t0)
      try {
        val confTxt = Source.fromFile(confFile).mkString
        val yaml = new Yaml();
        val yamlTopObject = yaml.load(confTxt)
        try {
          yamlTopObject.asInstanceOf[java.util.List[Object]].foreach { yamlObj: Object =>
            if (yamlObj == null) {
              log.error("SMGConfigServiceImpl.parseConf(" + confFile + "): CONFIG_ERROR: Received null yamlObj")
              return
            }
            try {
              val t = keyValFromMap(yamlObj.asInstanceOf[java.util.Map[String, Object]])
              if (t._1 == "$include") {
                processInclude(t._2.toString)
              } else if (t._1 == "$pre_fetch"){
                processPrefetch(t, confFile)
              } else if (t._1 == "$notify-command"){
                processNotifyCommand(t, confFile)
              } else if (t._1 == "$remote"){
                processRemote(t, confFile)
              } else if (t._1 == "$rra_def"){
                processRraDef(t, confFile)
              } else if (t._1.startsWith("$")) { // a global def
                processGlobal(t, confFile)
              } else if (t._1.startsWith("^")) { // an index def
                processIndex(t, isHidden = false, confFile)
              } else if (t._1.startsWith("~")) { // a "hidden" index def
                processIndex(t, isHidden = true, confFile)
              } else if (t._1.startsWith("+")) { // an aggregate object def
                processAggObject(t, confFile)
              } else { // an object def
                processObject(t, confFile)
              }
            } catch {
              case x: ClassCastException => log.error("SMGConfigServiceImpl.parseConf(" + confFile +
                "): CONFIG_ERROR:: bad object (" + yamlObj.toString + ") ex: " + x.toString)
            }
          } //foreach
        } catch {
          case e: ClassCastException => log.error("SMGConfigServiceImpl.parseConf(" + confFile + "): CONFIG_ERROR: bad toplevel object (expected List): " + yamlTopObject.getClass.toString)
        }
        val t1 = System.currentTimeMillis()
        log.debug("SMGConfigServiceImpl.parseConf(" + confFile + "): Finishing for " + (t1 - t0) + " milliseconds at " + t1)
      } catch {
        case e: Throwable => log.ex(e, "SMGConfigServiceImpl.parseConf(" + confFile + "): CONFIG_ERROR:: Unexpected exception: " + e.toString)
      }
    } // def parseConf

    def reloadPluginsConf(): Unit = {
      plugins.foreach { p =>
        p.reloadConf()
      }
    }

    parseConf(currentConfigFile)
    reloadPluginsConf()

    val pluginIndexes = plugins.flatMap(p => p.indexes)

    val indexConfsWithChildIds = (indexConfs ++ pluginIndexes).map { oi => SMGConfIndex(
        oi.id,
        oi.title,
        oi.flt,
        oi.cols,
        oi.rows,
        oi.aggOp,
        oi.xAgg,
        oi.period,
        oi.desc,
        oi.parentId,
        if (indexMap.contains(oi.id)) { indexMap(oi.id).toList } else oi.childIds,
        oi.disableHeatmap
      )
    }

    SMGConfIndex.buildChildrenSubtree(indexConfsWithChildIds)

    if (configuration.getString("smg.timeoutCommand").isDefined){
      val tmtCmd = configuration.getString("smg.timeoutCommand").get
      log.info("Overriding SMGCmd timeout command using " + tmtCmd)
      SMGCmd.setTimeoutCommand(tmtCmd)
    }

    if (configuration.getStringList("smg.executorCommand").isDefined) {
      val execSeq = configuration.getStringList("smg.executorCommand").get
      log.info("Overriding SMGCmd executor command using " + execSeq)
      SMGCmd.setExecutorCommand(execSeq)
    }

    val threadsPerIntervalMap: Map[Int,Int] = configuration.getConfig("smg.threadsPerIntervalMap") match {
      case Some(conf) => (for (i <- intervals.toList ; if conf.getInt("interval_" + i).isDefined) yield (i, conf.getInt("interval_" + i).get)).toMap
      case None => Map[Int,Int]()
    }
    ExecutionContexts.initializeUpdateContexts(intervals.toSeq, threadsPerIntervalMap, defaultThreadsPerInterval)

    // Process Index alert/notify configs, after all objects and indexes are defined
    // first get all plugin ObjectUpdates - filtering objects which has refObj defined and then using the
    // unique refObjs (._head after grouping by refObj id)
    val pluginUpdateObjects = plugins.flatMap(_.objects.filter(_.refObj.isDefined).map(_.refObj.get)).
      groupBy(_.id).map(t => (t._1,t._2.head))
    val digitsRx = "^\\d+$".r // digits-only regex
    // apply indexAlertConfs and indexNotifyConfs to each object
    (objectUpdateIds ++ pluginUpdateObjects).foreach { ot =>
      val ouid = ot._1
      val ou = ot._2
      indexAlertConfs.foreach { t3 =>
        val idx = t3._1
        val lbl = t3._2
        //if the alert label is an integer number - treat it as variable index specifier
        val lblAsIx = if (digitsRx.findFirstMatchIn(lbl).isDefined) lbl.toInt else -1
        val ac = t3._3
        if (idx.flt.matches(ou)) {
          ou.vars.zipWithIndex.foreach{ tv =>
            val v = tv._1
            val ix = tv._2
            if ((lblAsIx == ix) || (v.getOrElse("label", s"ds$ix") == lbl))
              addAlertConf(ou.id, ix, ac)
          }
        }
      }
      indexNotifyConfs.foreach { t3 =>
        val idx = t3._1
        val lbl = t3._2
        //if the alert label is an integer number - treat it as variable index specifier
        val lblAsIx = if (digitsRx.findFirstMatchIn(lbl).isDefined) lbl.toInt else -1
        val ac = t3._3
        if (idx.flt.matches(ou)) {
          ou.vars.zipWithIndex.foreach{ tv =>
            val v = tv._1
            val ix = tv._2
            if ((lblAsIx == ix) || (v.getOrElse("label", s"ds$ix") == lbl))
              addNotifyConf(ou.id, ix, ac)
          }
        }
      }

    }

    val objectAlertConfs = objectAlertConfMaps.map { t =>
      val oid = t._1
      val m = t._2.map(t => (t._1, t._2.toList)).toMap
      (t._1, SMGMonObjAlertConf(m))
    }

    val objectNotifyConfs = objectNotifyConfMaps.map { t =>
      val oid = t._1
      val m = t._2.map(t => (t._1, t._2.toList)).toMap
      (t._1, SMGMonObjNotifyConf(m))
    }

    val ret = SMGLocalConfig(
      globalConf.toMap,
      allViewObjectsConf.toList,
      indexConfsWithChildIds,
      SMGRrdConfig(
        if (globalConf.contains("$rrd_tool")) globalConf("$rrd_tool") else rrdTool,
        if (globalConf.contains("$rrd_socket")) Some(globalConf("$rrd_socket")) else None,
        if (globalConf.contains("$rrd_graph_width")) globalConf("$rrd_graph_width").toInt else 539,
        if (globalConf.contains("$rrd_graph_height")) globalConf("$rrd_graph_height").toInt else 135,
        globalConf.get("$rrd_graph_font")
      ),
      if (globalConf.contains("$img_dir")) globalConf("$img_dir") else imgDir,
      if (globalConf.contains("$url_prefix")) globalConf("$url_prefix") else urlPrefix,
      intervals.toSet,
      preFetches.toMap,
      remotes.toList,
      remoteMasters.toList,
      plugins.map( p => (p.pluginId, p.objects) ).toMap,
      plugins.map( p => (p.pluginId, p.preFetches)).toMap,
      objectAlertConfs.toMap,
      notifyCommands.toMap,
      objectNotifyConfs.toMap,
      hiddenIndexConfs.toMap
    )

    // validate pre_fetch commands - specifying invalid command id would be ignored
    ret.rrdObjects.foreach{ obj =>
      if (obj.preFetch.nonEmpty && preFetches.get(obj.preFetch.get).isEmpty) {
        log.error("SMGConfigServiceImpl.getNewConfig(" + currentConfigFile +
          "): CONFIG_ERROR: object specifies non existing pre_fetch id: " + obj)
      }
    }
    ret
  } // getNewConfig

  // register an Akka DeadLetter listener, to detect issues
  val deadLetterListener = actorSystem.actorOf(Props(classOf[SMGDeadLetterActor]))
  actorSystem.eventStream.subscribe(deadLetterListener, classOf[DeadLetter])
}
