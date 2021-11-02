package com.smule.smg.config

import java.io.File
import java.nio.file.{FileSystems, PathMatcher}
import java.util
import com.smule.smg.cdash.{CDashConfigItem, CDashboardConfig}
import com.smule.smg.config.SMGConfigParser.yobjMap
import com.smule.smg.core._
import com.smule.smg.grapher.SMGraphObject
import com.smule.smg.monitor._
import com.smule.smg.notify._
import com.smule.smg.notify.{SMGMonNotifyCmd, SMGMonNotifyConf, SMGMonNotifyConfObj}
import com.smule.smg.plugin.SMGPlugin
import com.smule.smg.remote.SMGRemote
import com.smule.smg.rrd.{SMGRraDef, SMGRrdConfig}
import org.yaml.snakeyaml.Yaml

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
  * Helper class to deal with Yaml parsing
  */

object SMGConfigParser {

  val defaultInterval: Int = 60 // seconds
  val defaultTimeout: Int = 30  // seconds

  val ALLOWED_UID_CHARS_REGEX_STR = "\\w\\._-"

  def validateOid(oid: String): Boolean = oid.matches("^[" + ALLOWED_UID_CHARS_REGEX_STR + "]+$")

  def getRrdFile(baseDir: String, oid: String, levelsDef: Option[DirLevelsDef], mkDirs: Boolean = true): String = {
    val myBaseDir = if (levelsDef.isEmpty)
      baseDir + File.separator
    else
      baseDir + File.separator + levelsDef.get.getHashLevelsPath(oid)
    val bdFile = new File(myBaseDir)
    if (mkDirs && !bdFile.exists())
      bdFile.mkdirs()
    myBaseDir + oid + ".rrd"
  }

  private def getListOfFiles(dir: String, matcher: PathMatcher, log: SMGLoggerApi):List[File] = {
    val d = new File(dir)
    if (d.exists && d.isDirectory) {
      d.listFiles.filter{ (f:File) =>
//        log.info(f.toPath);
        f.isFile && matcher.matches(f.toPath)}.toList.sortBy(f => f.toPath)
    } else {
      log.warn("SMGConfigParser.getListOfFiles: " + dir + " - not a directory")
      List[File]()
    }
  }

  def expandGlob(glob: String, log: SMGLoggerApi) : List[String] = {
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
    getListOfFiles(dir, matcher, log).map( (f: File) => f.getPath)
  }

  def yobjMap(yobj: Object): mutable.Map[String, Object] =
    yobj.asInstanceOf[java.util.Map[String, Object]].asScala

  def yobjList(yobj: Object): mutable.Seq[Object] =
    yobj.asInstanceOf[java.util.List[Object]].asScala
}

class SMGConfigParser(log: SMGLoggerApi) {

  // TODO
  val defaultInterval: Int = SMGConfigParser.defaultInterval // seconds
  val defaultTimeout: Int = SMGConfigParser.defaultTimeout  // seconds

  def validateOid(oid: String): Boolean = SMGConfigParser.validateOid(oid)

  def expandGlob(glob: String) : List[String] = SMGConfigParser.expandGlob(glob, log)

  // m should be one of
  // 1. (old style) map where the first key has null value (key would be the id) { k -> null, a -> b, ...}
  // 2. (old style) map with only one key (returned as the "id key") where value is either
  //   2.1. a string (global val) { k -> v }  (this is also valid "new style")
  //   2.2. a map representing the object data { k -> {a -> b, ...} }
  // 3. (new style) map { "id" -> k, "type" -> "...", a -> b, c -> d .. }
  //   3.1. if it has a type -> "auto" pair or doesn't have a type key,
  //        the map value for "id" key is used as return key
  //   3.2. if type is diff than "auto" - type value is returned as the key
  def keyValFromMap(m: util.Map[String, Object]): (String,Object) = {
    val firstKey = m.asScala.keys.head
    if (m.get(firstKey) == null){
      // old style config - map where the first key has null value and that key is the object id
      // { id -> null, a -> b, ..,
      m.remove(firstKey)
      (firstKey, m)
    } else {
      if (m.size() == 1) {
        // old style map nested under id key, or a global
        // { id -> {a -> b, ...} }
        (firstKey, m.get(firstKey))
      } else {
        // a new style config  { type -> ..., id -> .... }
        if (m.containsKey("type")) {
          var retKey = m.get("type").toString
          if ((retKey == "auto") && (m.get("id") != null)) {
            retKey = m.remove("id").toString
          } else {
            if (!retKey.startsWith("$")) retKey = "$" + retKey
          }
          (retKey, m)
        } else { // new style auto type object
          if (m.containsKey("id")) {
            val retKey = m.remove("id").toString
            (retKey, m)
          } else { // error?
            log.warn(s"SMGConfigParser.keyValFromMap: unexpected config object: $firstKey : ${m.asScala.toMap}")
            (firstKey, m)
          }
        }
      }
    }
  }

  def getRrdType(ymap: mutable.Map[String, Object], default: Option[String]): String = {
    val realDefault = default.getOrElse("GAUGE")
    // XXX support for both rrdType (deprecated) and rrd_type syntax
    if (ymap.contains("rrd_type"))
      ymap("rrd_type").toString
    else ymap.getOrElse("rrdType", realDefault).toString
  }

  def yamlVarsToVars(yamlVars: Object): List[SMGObjectVar] = {
    yamlVars.asInstanceOf[util.ArrayList[util.Map[String, Object]]].asScala.toList.map(
      (m: util.Map[String, Object]) => SMGObjectVar(m.asScala.map { t => (t._1, t._2.toString) }.toMap)
    )
  }

  def ymapVars(ymap: mutable.Map[String, Object]): List[SMGObjectVar] = {
    if (ymap.contains("vars")) {
      yamlVarsToVars(ymap("vars"))
    } else {
      List()
    }
  }

  private def ymapCdefVars(ymap: mutable.Map[String, Object]): List[SMGObjectVar] = {
    if (ymap.contains("cdef_vars")) {
      yamlVarsToVars(ymap("cdef_vars"))
    } else {
      List()
    }
  }

  private def sourceFromFile(fn:String): String = {
    SMGFileUtil.getFileContents(fn)
  }

  object ForwardObjectRef extends Enumeration {
    type objType = Value
    val GRAPH_OBJ, AGG_OBJ = Value
  }

  case class ForwardObjectRef(oid: String,
                              confFile: String,
                              ymap: mutable.Map[String, Object],
                              oType: ForwardObjectRef.Value
                             )

  /**
    * Generate a new immutable configuration object, using mutable helpers in the process
    *
    * @return - a newly parsed SMGLocalConfig from the top-level config file ("smg.config" configuration value,
    *         "/etc/smg/config.yml" by default)
    */
  def getNewConfig(plugins: Seq[SMGPlugin], topLevelConfigFile: String): SMGLocalConfig = {
    val globalConf = mutable.Map[String,String]()
    val allViewObjectIds  = ListBuffer[String]()
    val allViewObjectsById  = mutable.Map[String,SMGObjectView]()
    val objectIds = mutable.Set[String]()
    val objectUpdateIds = mutable.Map[String, SMGObjectUpdate]()
    val indexAlertConfs = ListBuffer[(SMGConfIndex, String, SMGMonAlertConfVar)]()
    val indexNotifyConfs = ListBuffer[(SMGConfIndex, String, SMGMonNotifyConf)]()
    val indexObjectLevelNotifyConfs = ListBuffer[(SMGConfIndex, SMGMonNotifyConf)]()
    var indexConfs = ListBuffer[SMGConfIndex]()
    val indexIds = mutable.Set[String]()
    val indexMap = mutable.Map[String,ListBuffer[String]]()
    val hiddenIndexConfs = mutable.Map[String, SMGConfIndex]()
    val objectAlertConfMaps = mutable.Map[String,mutable.Map[Int, ListBuffer[SMGMonAlertConfVar]]]()
    val objectNotifyConfMaps = mutable.Map[String,mutable.Map[Int, ListBuffer[SMGMonNotifyConf]]]()
    var rrdDir = SMGLocalConfig.DEFAULT_RRD_DIR
    var levelsDef: Option[DirLevelsDef] = None
    val rrdTool = "rrdtool"
    val imgDir = "public/smg"
    val urlPrefix: String = "/assets/smg"
    val intervals = mutable.Set[Int](plugins.map(_.interval).filter(_ != 0):_*)
    val preFetches = mutable.Map[String, SMGPreFetchCmd]()
    val notifyCommands = mutable.Map[String, SMGMonNotifyCmd]()
    val remotes = ListBuffer[SMGRemote]()
    val remoteMasters = ListBuffer[SMGRemote]()
    val rraDefs = mutable.Map[String, SMGRraDef]()
    val cDashboardConfigs = ListBuffer[CDashboardConfig]()
    val authUsers = ListBuffer[AuthUserConfig]()
    val configErrors = ListBuffer[String]()
    val intervalConfs = mutable.Map[Int, IntervalThreadsConfig]()

    val pluginChecks = plugins.flatMap(p => p.valueChecks.map { t => (p.pluginId + "-" + t._1, t._2)}).toMap

    // Agg and graph objects contain references to other objects which may be defined later,
    // on first pass we only store their position within allViewObjectIds and record state for later use
    // note that agg objects can reference view objects and view objects can reference agg objects
    // in that case the referencing object must be defined after the referenced object
    val forwardObjects = ListBuffer[ForwardObjectRef]()

    def processConfigError(confFile: String, msg: String, isWarn: Boolean = false) = {
      val marker = if (isWarn) "CONFIG_WARNING" else "CONFIG_ERROR"
      val mymsg = s"$marker: $confFile: $msg"
      val logmsg = s"SMGConfigService.getNewConfig: $mymsg"
      if (isWarn)
        log.warn(logmsg)
      else
        log.error(logmsg)
      configErrors += mymsg
    }

    def addAlertConf(oid: String, ix: Int, ac: SMGMonAlertConfVar) = {
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

    def checkFetchCommandNotifyConf(pfId: String, notifyConf: Option[SMGMonNotifyConf], confFile: String): Unit = {
      if (notifyConf.isDefined){
        val nc = notifyConf.get
        val lb = ListBuffer[String]()
        if (nc.anom.nonEmpty) {
          lb += s"anom=${nc.anom.mkString(",")}"
        }
        if (nc.warn.nonEmpty) {
          lb += s"warn=${nc.warn.mkString(",")}"
        }
        if (nc.crit.nonEmpty) {
          lb += s"warn=${nc.crit.mkString(",")}"
        }
        if (lb.nonEmpty) {
          processConfigError(confFile,
            s"checkFetchCommandNotifyConf: $pfId specifies irrelevant alert level notification commands (will be ignored): ${lb.mkString(", ")}")
        }
      }
    }

    def getDelay(yamlMap: mutable.Map[String, Object]): Double = {
      yamlMap.get("delay").map(x =>
        Try(x.toString.toDouble).getOrElse(0.0)).getOrElse(0.0)
    }

    def processPrefetch(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
      if (yamlMap.contains("id") && yamlMap.contains("command")) {
        val id = yamlMap("id").toString
        if (!checkOid(id)) {
          processConfigError(confFile,
            s"processPrefetch: id already defined (ignoring): $id: " + yamlMap.toString)
        } else {
          val cmd = SMGCmd(yamlMap("command").toString, yamlMap.getOrElse("timeout", 30).asInstanceOf[Int]) //  TODO get 30 from a val
          val parentPfStr = yamlMap.getOrElse("pre_fetch", "").toString
          val parentPf = if (parentPfStr == "") None else Some(parentPfStr)
          val ignoreTs = yamlMap.contains("ignorets") && (yamlMap("ignorets").toString != "false")
          val childConc = if (yamlMap.contains("child_conc"))
            yamlMap("child_conc").asInstanceOf[Int]
          else 1
          val passData = yamlMap.contains("pass_data") && (yamlMap("pass_data").toString == "true")

          val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, id, yamlMap.toMap.map(kv => (kv._1, kv._2.toString)))
          checkFetchCommandNotifyConf(id, notifyConf, confFile)
          preFetches(id) = SMGPreFetchCmd(id = id,
            command = cmd,
            desc = if (yamlMap.contains("desc")) Some(yamlMap("desc").toString) else None,
            preFetch = parentPf,
            ignoreTs = ignoreTs,
            childConc = Math.max(1, childConc),
            notifyConf = notifyConf,
            passData = passData,
            delay = getDelay(yamlMap)
          )
        }
      } else {
        processConfigError(confFile, "processPrefetch: $pre_fetch yamlMap does not have command and id: " + yamlMap.toString)
      }
    }

    def processNotifyCommand(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
      if (yamlMap.contains("id") && yamlMap.contains("command")) {
        val id = yamlMap("id").toString
        if (notifyCommands.contains(id)) {
          processConfigError(confFile,
            s"processNotifyCommand: notify command id already defined (ignoring): $id: " + yamlMap.toString)
        } else {
          notifyCommands(id) = SMGMonNotifyCmd(id, yamlMap("command").toString, yamlMap.getOrElse("timeout", 30).asInstanceOf[Int])
        }
      } else {
        processConfigError(confFile, "processNotifyCommand: $notify-command yamlMap does not have command and id: " + yamlMap.toString)
      }
    }

    def processRemote(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
      val rmtOpt = SMGRemote.fromYamlMap(yamlMap)
      if (rmtOpt.isDefined) {
        if (rmtOpt.get.slaveId.isDefined) {
          remoteMasters += rmtOpt.get
        } else
          remotes += rmtOpt.get
      } else {
        processConfigError(confFile, "processRemote: $remote yamlMap does not have id and url: " + yamlMap.toString)
      }
    }

    def processRraDef(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
      if (yamlMap.contains("id") && yamlMap.contains("rra")) {
        val rid = yamlMap("id").asInstanceOf[String]
        if (rraDefs.contains(rid)) {
          processConfigError(confFile, "processRraDef: duplicate $rra_def id: " + rid)
        } else {
          rraDefs(rid) = SMGRraDef(rid, yamlMap("rra").asInstanceOf[util.ArrayList[String]].asScala.toList)
          log.debug("SMGConfigServiceImpl.processRraDef: Added new rraDef: " + rraDefs(rid))
        }
      } else {
        processConfigError(confFile, "processRraDef: $rra_def yamlMap does not have id and rra: " + yamlMap.toString)
      }
    }

    def parseCDashConfigItem(ymap: mutable.Map[String,Object], confFile: String): Option[CDashConfigItem] = {
      try {
        Some(
          CDashConfigItem.fromYamlMap(ymap)
        )
      } catch {
        case t: Throwable => {
          processConfigError(confFile, s"parseCDashConfigItem: Exception parsing item ($ymap): " + t.toString)
          None
        }
      }
    }

    def processCustomDashboard(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
      if (yamlMap.contains("id") && yamlMap.contains("items")){
        val cdashId = yamlMap("id").toString
        val titleOpt = if(yamlMap.contains("title")) Some(yamlMap("title").toString) else None
        val myRefresh = Try(yamlMap("refersh").asInstanceOf[Int]).getOrElse(300)
        val myItemsOpt = Try(yamlMap("items").asInstanceOf[java.util.List[Object]].asScala).toOption
        if (myItemsOpt.isDefined) {
          val parsedItems = myItemsOpt.get.toList.flatMap { itm =>
            parseCDashConfigItem(itm.asInstanceOf[java.util.Map[String, Object]].asScala, confFile)
          }
          cDashboardConfigs += CDashboardConfig(id = cdashId, title = titleOpt, refresh = myRefresh, items = parsedItems)
        } else {
          processConfigError(confFile, s"processCustomDashboard: $$cdash (id=$cdashId) yamlMap has invalid items: " + yamlMap.toString)
        }
      } else {
        processConfigError(confFile, "processCustomDashboard: $cdash yamlMap does not have id and items: " + yamlMap.toString)
      }
    }

    def processGlobal( t: (String,Object) , confFile: String ): Unit = {
      val key = t._1
      val sval = t._2.toString
      if (globalConf.contains(key)) {
        processConfigError(confFile, "processGlobal: overwriting duplicate global " +
          s"value: key=$key oldval=${globalConf(key)} newval=$sval", isWarn = true)
      }
      if (key == "$rrd_dir") {
        rrdDir = sval
        new File(rrdDir).mkdirs()
      }
      var ignore = false
      if (key == "$rrd_dir_levels") {
        if (levelsDef.isDefined) {
          processConfigError(confFile, "processGlobal: $rrd_dir_levels global " +
            s"can not be specified more than once", isWarn = true)
          ignore = true
        } else {
          levelsDef = DirLevelsDef.parse(sval)
          if (levelsDef.isEmpty)
            processConfigError(confFile, "processGlobal: $rrd_dir_levels global " +
              s"contains invalid value sval=$sval", isWarn = true)
        }
      }
      if (!ignore)
        globalConf(key) = sval
    }

    def processIndex( t: (String,Object), isHidden: Boolean, confFile: String ): Unit = {
      val idxId = t._1.substring(1)
      if (indexIds.contains(idxId)) {
        processConfigError(confFile, "processIndex: skipping duplicate index with id: " + t._1)
      } else {
        try {
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
          val idx = new SMGConfIndex(idxId, ymap.toMap)
          indexIds += idxId
          if (isHidden) {
            if (hiddenIndexConfs.contains(idxId)) { // not really possibe
              processConfigError(confFile, "processIndex: detected duplicate hidden index with id: " + t._1)
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
          // notify- applied at the index level map applies to fetch commands (including update objects)
          val src = if (isHidden) SMGMonAlertConfSource.HINDEX else SMGMonAlertConfSource.INDEX
          val commandNotifyConf = SMGMonNotifyConf.fromVarMap(src, idx.id, ymap.map(t => (t._1,t._2.toString)).toMap)
          if (commandNotifyConf.isDefined)
            indexObjectLevelNotifyConfs += Tuple2(idx, commandNotifyConf.get)
          if (ymap.contains("alerts")){
            val alertsLst = ymap("alerts").asInstanceOf[java.util.ArrayList[java.util.Map[String, Object]]].asScala
            alertsLst.foreach { m =>
              val sm = m.asScala.map(t => (t._1, t._2.toString)).toMap
              val ac = SMGMonAlertConfVar.fromVarMap(src, idx.id, sm, pluginChecks)
              if (ac.isDefined) indexAlertConfs += Tuple3(idx, sm.getOrElse("label","ds" + idx), ac.get)
              val nc = SMGMonNotifyConf.fromVarMap(src, idx.id, sm)
              if (nc.isDefined) indexNotifyConfs += Tuple3(idx, sm.getOrElse("label","ds" + idx), nc.get)
            }
          }
        } catch {
          case x : ClassCastException => processConfigError(confFile, s"processIndex: bad index tuple ($t) ex: $x")
        }
      }
    }

    def checkOid(oid: String): Boolean = validateOid(oid) &&
      (!objectIds.contains(oid)) && (!preFetches.contains(oid))

    def processObjectVarsAlertAndNotifyConfs(ymap: mutable.Map[String, Object], oid: String): List[SMGObjectVar] = {
      val myYmapVars = ymapVars(ymap)
      // parse alert confs
      myYmapVars.zipWithIndex.foreach { t =>
        val ix = t._2
        val m = t._1
        val ac = SMGMonAlertConfVar.fromVarMap(SMGMonAlertConfSource.OBJ, oid, m.m, pluginChecks)
        if (ac.isDefined) {
          addAlertConf(oid, ix, ac.get)
        }
      }
      // parse notify confs
      myYmapVars.zipWithIndex.foreach { t =>
        val ix = t._2
        val m = t._1
        val nc = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid, m.m)
        if (nc.isDefined) {
          addNotifyConf(oid, ix, nc.get)
        }
      }
      // exclude alert- and notify- defs from the vars maps so it is not passed around remotes and does not mess up aggregation
      val ymapFilteredVars = myYmapVars.map { m =>
        m.m.filter(t => !(SMGMonAlertConfVar.isAlertKey(t._1) || SMGMonNotifyConf.isNotifyKey(t._1)) )
      }
      ymapFilteredVars.map(x => SMGObjectVar(x))
    }

    def getRraDef(confFile: String, oid: String, ymap: mutable.Map[String, Object]): Option[SMGRraDef] = {
      if (ymap.contains("rra")) {
        val rid = ymap("rra").toString
        if (rraDefs.contains(rid)) {
          rraDefs.get(rid)
        } else {
          processConfigError(confFile,
            s"processObject: ignoring non-existing rra value rra=$rid oid=$oid")
          None
        }
      } else None
    }

    def lookupObjectView(k: String, pluginObjs:  Map[String, SMGObjectView]): Option[SMGObjectView] = {
      val ret = allViewObjectsById.get(k)
      if (ret.isDefined)
        ret
      else
        pluginObjs.get(k)
    }

    def getLabels(ymap: mutable.Map[String, Object]): Map[String,String] ={
      if (ymap.contains("labels") && (ymap("labels") != null)) {
        ymap("labels").asInstanceOf[java.util.Map[String, Object]].
          asScala.map { case (k, v) => (k, v.toString)}.toMap
      } else Map()
    }

    def createGraphObject(oid: String,
                          ymap: mutable.Map[String, Object],
                          confFile: String,
                          pluginViewObjs: Map[String, SMGObjectView]
                         ): Unit = {
      try {
        val refid = ymap("ref").toString
        val refObjOpt = lookupObjectView(refid, pluginViewObjs)
        if (refObjOpt.isEmpty || refObjOpt.get.refObj.isEmpty){
          processConfigError(confFile,
            s"processObject: skipping graph object object with non existing update ref refid=$refid oid=$oid")
        } else {
          val refobj = refObjOpt.get
          val obj = SMGraphObject(
            id = oid,
            interval = refobj.interval,
            vars = refobj.vars,
            cdefVars = ymapCdefVars(ymap),
            title = ymap.getOrElse("title", refobj.title).toString,
            stack = ymap.getOrElse("stack", refobj.stack).asInstanceOf[Boolean],
            gvIxes = ymap.getOrElse("gv", new util.ArrayList[Int]()).asInstanceOf[util.ArrayList[Int]].asScala.toList,
            rrdFile = refobj.rrdFile,
            refObj = refobj.refObj,
            rrdType = refobj.rrdType,
            labels = getLabels(ymap)
          )
          allViewObjectsById(obj.id) = obj
        }
      } catch {
        case t: Throwable =>  processConfigError(confFile,
          s"createGraphObject: unexpected error creating Graph object - oid=$oid")
      }
    }

    def processObject( t: (String,Object), confFile: String ): Unit = {
      val oid = t._1
      if (!checkOid(oid)){
        processConfigError(confFile, "processObject: skipping object with invalid or duplicate id: " + oid)
      } else {
        try {
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
          // check if we are defining a graph object vs rrd object (former instances reference the later)
          if (ymap.contains("ref")) {
            // a graph object - just record the id at the correct position and keep the ymap for later
            objectIds += oid
            allViewObjectIds += oid
            forwardObjects += ForwardObjectRef(oid, confFile, ymap, ForwardObjectRef.GRAPH_OBJ)
          } else { //no ref - plain rrd object
            if (ymap.contains("vars") && ymap.contains("command")) {
              val rraDef = getRraDef(confFile, oid, ymap)
              val ymapFilteredVars = processObjectVarsAlertAndNotifyConfs(ymap, oid)
              val myRrdType = getRrdType(ymap, None)
              val myDefaultInterval = globalConf.getOrElse("$default-interval", defaultInterval.toString).toInt
              val myDefaultTimeout = globalConf.getOrElse("$default-timeout", defaultTimeout.toString).toInt
              val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid, ymap.toMap.map(kv => (kv._1, kv._2.toString)))
              checkFetchCommandNotifyConf(oid, notifyConf, confFile)
              //find all parents. avoid infinite loop caused by circular dependencies due to bad conf
              var depth = 0
              val parentIds = ListBuffer[String]()
              var preFetch = if (ymap.contains("pre_fetch")) Some(ymap("pre_fetch").toString) else None
              while (preFetch.isDefined && depth < 100){
                parentIds += preFetch.get
                preFetch = preFetches.get(preFetch.get).flatMap(_.preFetch)
                depth += 1
              }
              if(depth> 50){
                processConfigError(confFile,
                  s"processObject: ${oid}: parentIds depth exceeds 50: ${depth}", isWarn = depth < 100)
              }
              var confInterval = ymap.getOrElse("interval", myDefaultInterval).asInstanceOf[Int]
              if (confInterval <= 0){
                processConfigError(confFile,
                  s"processObject: ${oid}: config interval is not positove: ${confInterval} " +
                    s"(assuming default)", isWarn = true)
                confInterval = SMGConfigParser.defaultInterval
              }
              // dataDelay is deprecated, use data_delay
              val dataDelay = ymap.getOrElse("data_delay",
                ymap.getOrElse("dataDelay", 0).asInstanceOf[Int]
              ).asInstanceOf[Int]
              val obj = SMGRrdObject(
                id = oid,
                parentIds = parentIds.toList,
                command = SMGCmd(ymap("command").toString, ymap.getOrElse("timeout", myDefaultTimeout).asInstanceOf[Int]),
                vars = ymapFilteredVars,
                title = ymap.getOrElse("title", oid).toString,
                rrdType = myRrdType,
                interval = confInterval,
                dataDelay = dataDelay,
                delay = getDelay(ymap),
                stack = ymap.getOrElse("stack", false).asInstanceOf[Boolean],
                preFetch = parentIds.headOption,
                rrdFile = Some(SMGConfigParser.getRrdFile(rrdDir, oid, levelsDef)),
                rraDef = rraDef,
                rrdInitSource = if (ymap.contains("rrd_init_source")) Some(ymap("rrd_init_source").toString) else None,
                notifyConf = notifyConf,
                labels = getLabels(ymap)
              )
              objectIds += oid
              objectUpdateIds(oid) = obj
              allViewObjectIds += obj.id
              allViewObjectsById(obj.id) = obj
              intervals += obj.interval
            } else {
              processConfigError(confFile, s"RRD object definition does not contain command and vars: $oid: ${ymap.toString}")
            }
          }
        } catch {
          case x : ClassCastException => processConfigError(confFile,
            s"processObject: bad object tuple ($t) ex: $x")
        }
      }
    }


    def aggObjectOp(oid: String,
                    ymap: mutable.Map[String, Object],
                    confFile: String): String = {
      val confOp = ymap.getOrElse("op", "SUM")
      confOp match {
        case "SUMN" => "SUMN"
        case "AVG" => "AVG"
        case "SUM" => "SUM"
        case "MAX" => "MAX"
        case "MIN" => "MIN"
        case x: String => {
          if (x.startsWith("RPN:"))
            x
          else {
            processConfigError(confFile,
              s"createAggObject: unsupported agg op for $oid: $x (assuming SUM)", isWarn = true)
            "SUM"
          }
        }
      }
    }

    def filterObjectViews(flt: SMGFilter, pluginObjs:  Map[String, SMGObjectView]): Seq[SMGObjectView] = {
      allViewObjectIds.flatMap { void => allViewObjectsById.get(void) }.filter { vo =>
        flt.matches(vo)
      } ++ pluginObjs.toSeq.map(_._2).filter { vo =>
        flt.matches(vo)
      }
    }

    def idsToAggObjectMembers(oid: String,
                              ids: Seq[String],
                              confFile: String,
                              pluginViewObjs: Map[String, SMGObjectView]): Seq[Option[SMGObjectUpdate]] = {
      ids.map { ovid =>
        val ret = lookupObjectView(ovid, pluginViewObjs).flatMap(_.refObj)
        if (ret.isEmpty) {
          processConfigError(confFile, "createAggObject: agg object references " +
            s"undefined object: $oid, ref id=$ovid (agg object will be ignored)")
        }
        ret
      }
    }

    def filterToAggObjectMembers(oid: String,
                                 flt: SMGFilter,
                                 confFile: String,
                                 pluginViewObjs: Map[String, SMGObjectView]): Seq[Option[SMGObjectUpdate]] = {
      val ret = filterObjectViews(flt, pluginViewObjs).map(_.refObj)
      var successRet = true
      if (ret.nonEmpty) {
        ret.tail.foreach { t =>
          if (ret.head.get.vars.lengthCompare(t.get.vars.size) != 0){
            processConfigError(confFile, "createAggObject: agg object references " +
              s"invalid object via filter: $oid, ref id=${t.get.id}. (agg object will be ignored)")
            successRet = false
          }
        }
      } else {
        processConfigError(confFile, "createAggObject: agg object references " +
          s"invalid object via filter: $oid, empty filter result. (agg object will be ignored)")
        successRet = false
      }
      if (successRet)
        ret
      else
        Seq()
    }

    def createAggObject(oid: String,
                        ymap: mutable.Map[String, Object],
                        confFile: String,
                        pluginViewObjs: Map[String, SMGObjectView]
                       ): Unit = {
      try {
        val op = aggObjectOp(oid, ymap, confFile)

        if (ymap.contains("ids") || ymap.contains("filter")) {

          val idObjOpts = if (ymap.contains("ids")){
            val ids = ymap("ids").asInstanceOf[util.ArrayList[String]].asScala.toList
            idsToAggObjectMembers(oid, ids, confFile, pluginViewObjs)
          } else Seq()

          val fltObjOpts = if (ymap.contains("filter")){
            val flt = SMGFilter.fromYamlMap(yobjMap(ymap("filter")).toMap)
            filterToAggObjectMembers(oid, flt, confFile, pluginViewObjs)
          } else Seq()

          val objOpts = idObjOpts ++ fltObjOpts

          if (objOpts.nonEmpty && objOpts.forall(_.isDefined)) {
            val objs = objOpts.map(_.get)
            val myRraDef = getRraDef(confFile, oid, ymap)
            val myVars = if (ymap.contains("vars")) {
              processObjectVarsAlertAndNotifyConfs(ymap, oid)
            } else {
              // XXX no vars defined, use first object's ones but filter out the "max" value
              // which is likely wrong for the SUM object.
              objs.head.vars.map { v => SMGObjectVar(v.m.filter { case (k, vv) => k != "max" }) }
            }
            val myDataDelay = if (ymap.contains("dataDelay")){
              ymap("dataDelay").asInstanceOf[Int]
            } else objs.head.dataDelay
            val myRrdType = getRrdType(ymap, Some(objs.head.rrdType))
            // sanity check the objects, all must have at least myVars.size vars
            // TODO: more thorough validation?
            if (!objs.exists { ou =>
              val ret = ou.vars.size < myVars.size
              if (ret) {
                processConfigError(confFile, "createAggObject: agg object references " +
                  s"invalid object (${ou.vars.size} vars less than ${myVars.size}): $oid, ref id=${ou.id} " +
                  s"(agg object will be ignored)")
              }
              ret
            }) {
              val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid, ymap.toMap.map(kv => (kv._1, kv._2.toString)))
              checkFetchCommandNotifyConf(oid, notifyConf, confFile)
              val rrdAggObj = SMGRrdAggObject(
                id = oid,
                ous = objs,
                aggOp = op,
                vars = myVars,
                title = ymap.getOrElse("title", oid).toString,
                rrdType = myRrdType,
                interval = objs.head.interval,
                dataDelay = myDataDelay,
                stack = ymap.getOrElse("stack", false).asInstanceOf[Boolean],
                rrdFile = Some(SMGConfigParser.getRrdFile(rrdDir, oid, levelsDef)),
                rraDef = myRraDef,
                rrdInitSource = if (ymap.contains("rrd_init_source")) Some(ymap("rrd_init_source").toString) else None,
                notifyConf = notifyConf,
                labels = getLabels(ymap)
              )
              objectUpdateIds(oid) = rrdAggObj
              allViewObjectsById(rrdAggObj.id) = rrdAggObj
            } // else - error already logged (checking for incompatible vars)
          } // else - errors already logged (checking for invalid object refs)
        } else {
          processConfigError(confFile,
            s"createAggObject: agg object definition without ids or filter: $oid, ignoring")
        }
      } catch {
        case t: Throwable =>
          log.ex(t, s"createAggObject: unexpected error creating Aggregate object - oid=$oid")
          processConfigError(confFile,
          s"createAggObject: unexpected error creating Aggregate object - oid=$oid")
      }
    }

    def processAggObject( t: (String,Object), confFile: String ): Unit = {
      val oid =  t._1.substring(1) // strip the +
      if (!checkOid(oid)){
        processConfigError(confFile, "processAggObject: skipping agg object with invalid or duplicate id: " + oid)
      } else {
        try {
          // only record the id/position and yaml for later
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
          objectIds += oid
          allViewObjectIds += oid
          forwardObjects += ForwardObjectRef(oid, confFile, ymap, ForwardObjectRef.AGG_OBJ)
        } catch {
          case x : ClassCastException => processConfigError(confFile,
            s"processAggObject: bad object tuple ($t) ex: $x")
        }
      }
    }

    def processIntervalConf( t: (String,Object), confFile: String ): Unit = {
      val ymap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
      val intvl = Try(ymap("interval").asInstanceOf[Int]).toOption
      if (intvl.isEmpty){
        processConfigError(confFile,
          s"processIntervalConf: skipping interval_def with invalid interval: $ymap")
      }
      val conf = IntervalThreadsConfig(
        interval = intvl.get,
        numThreads = ymap.get("threads").map(_.asInstanceOf[Int]).
          getOrElse(IntervalThreadsConfig.DEFAULT_NUM_THREADS),
        poolType =  ymap.get("pool").map(x => IntervalThreadsConfig.poolTypeFromStr(x.toString)).
          getOrElse(IntervalThreadsConfig.DEFAULT_POOL_TYPE)
      )
      if (intervalConfs.contains(conf.interval)){
        processConfigError(confFile, s"processIntervalConf: duplicate interval_defs: " +
          s"old=${intervalConfs(conf.interval).inspect} new=${conf.inspect}", isWarn = true)
      }
      intervalConfs(conf.interval) = conf
    }

    def processAuthUser( t: (String,Object), confFile: String ): Unit = {
      val ymap = t._2.asInstanceOf[java.util.Map[String, Object]].asScala
      authUsers += AuthUserConfig(t._1, ymap.toMap)
    }

    def parseConf(confFile: String): Unit = {
      val t0 = System.currentTimeMillis()
      log.debug("SMGConfigServiceImpl.parseConf(" + confFile + "): Starting at " + t0)
      try {
        val confTxt = sourceFromFile(confFile)
        val yaml = new Yaml()
        val yamlTopObject: Object = yaml.load(confTxt)
        try {
          yamlTopObject.asInstanceOf[java.util.List[Object]].asScala.foreach { yamlObj: Object =>
            if (yamlObj == null) {
              processConfigError(confFile, "parseConf: Received null yamlObj")
              return
            }
            try {
              val t = keyValFromMap(yamlObj.asInstanceOf[java.util.Map[String, Object]])
              if (t._1 == "$include") {
                processInclude(t._2.toString)
              } else if ((t._1 == "$pre_fetch") || (t._1 == "$pf")){
                processPrefetch(t, confFile)
              } else if (t._1 == "$notify-command"){
                processNotifyCommand(t, confFile)
              } else if (t._1 == "$remote"){
                processRemote(t, confFile)
              } else if (t._1 == "$rra_def"){
                processRraDef(t, confFile)
              } else if (t._1 == "$cdash"){
                processCustomDashboard(t, confFile)
              } else if (t._1 == "$interval_def"){
                processIntervalConf(t, confFile)
              } else if (t._1.startsWith("$auth-user-")) { // a user or token def
                processAuthUser(t, confFile)
              } else if (t._1.startsWith("$")) { // a global def
                processGlobal(t, confFile)
              } // type: auto below
                else if (t._1.startsWith("^")) { // an index def
                processIndex(t, isHidden = false, confFile)
              } else if (t._1.startsWith("~")) { // a "hidden" index def
                processIndex(t, isHidden = true, confFile)
              } else if (t._1.startsWith("+")) { // an aggregate object def
                processAggObject(t, confFile)
              } else { // an object def
                processObject(t, confFile)
              }
            } catch {
              case x: ClassCastException => processConfigError(confFile,
                s"parseConf: bad yaml object - wrong type ($yamlObj) ex: $x")
            }
          } //foreach
        } catch {
          case e: ClassCastException => processConfigError(confFile, "bad top level object (expected List): " + yamlTopObject.getClass.toString)
        }
        val t1 = System.currentTimeMillis()
        val dt = t1 - t0
        if (dt > 500){
          log.warn("SMGConfigServiceImpl.parseConf(" + confFile + "): Finishing for more than 500ms: " + dt + " milliseconds")
        }
        log.debug("SMGConfigServiceImpl.parseConf(" + confFile + "): Finishing for " + dt + " milliseconds at " + t1)
      } catch {
        case e: Throwable => processConfigError(confFile, s"parseConf: unexpected exception: $e")
      }
    } // def parseConf

    def createRrdConf = {
      val socketOpt = (if (globalConf.contains("$rrd_socket")) Some(globalConf("$rrd_socket")) else None).map { sock =>
        if (sock.contains(":")){
          sock
        } else "unix:" + sock
      }
      SMGRrdConfig(
        rrdTool = if (globalConf.contains("$rrd_tool")) globalConf("$rrd_tool") else rrdTool,
        rrdToolSocket = socketOpt,
        rrdcachedSocatCommand = globalConf.getOrElse("$rrdcached_socat_command", "socat"),
        rrdcachedUpdateBatchSize = globalConf.get("$rrdcached_update_batch_size").flatMap(x => Try(x.toInt).toOption).getOrElse(1),
        rrdcachedFlushAllOnRun = globalConf.get("$rrdcached_flush_all_on_run").flatMap(x => Try(x.toBoolean).toOption).getOrElse(false),
        rrdcachedFlushOnRead = globalConf.get("$rrdcached_flush_on_read").flatMap(x => Try(x.toBoolean).toOption).getOrElse(true),
        rrdGraphWidth = globalConf.get("$rrd_graph_width").flatMap(x => Try(x.toInt).toOption).getOrElse(SMGRrdConfig.defaultGraphWidth),
        rrdGraphHeight = globalConf.get("$rrd_graph_height").flatMap(x => Try(x.toInt).toOption).getOrElse(SMGRrdConfig.defaultGraphHeight),
        rrdGraphFont = globalConf.get("$rrd_graph_font"),
        dataPointsPerPixel = globalConf.get("$rrd_graph_dppp").flatMap(x => Try(x.toInt).toOption).getOrElse(SMGRrdConfig.defaultDataPointsPerPixel),
        dataPointsPerImageOpt = globalConf.get("$rrd_graph_dppi").flatMap(x => Try(x.toInt).toOption),
        rrdGraphWidthPadding = globalConf.get("$rrd_graph_padding").flatMap(x => Try(x.toInt).toOption),
        maxArgsLengthOpt = globalConf.get("$rrd_max_args_len").flatMap(x => Try(x.toInt).toOption)
      )
    }

    def processForwardObjects(pluginViewObjs: Map[String, SMGObjectView]): Unit = {
      forwardObjects.foreach { fwdef =>
        fwdef.oType match {
          case ForwardObjectRef.GRAPH_OBJ => createGraphObject(fwdef.oid, fwdef.ymap, fwdef.confFile, pluginViewObjs)
          case ForwardObjectRef.AGG_OBJ => createAggObject(fwdef.oid, fwdef.ymap, fwdef.confFile, pluginViewObjs)
          case x => {
            log.error(s"Unexpected forwardObject Type: $x, fwdef=$fwdef ")
          }
        }
      }
    }

    def reloadPluginsConf(): Unit = {
      plugins.foreach { p =>
        p.reloadConf()
      }
    }

    val t0 = System.currentTimeMillis()
    parseConf(topLevelConfigFile)
    val t1 = System.currentTimeMillis()
    log.info(s"SMGConfigParser.getNewConfig: top-level config parsed in ${t1 - t0}ms ($topLevelConfigFile)")
    reloadPluginsConf()
    val t2 = System.currentTimeMillis()
    log.info(s"SMGConfigParser.getNewConfig: plugins reloaded in ${t2 - t1}ms (plugins.size=${plugins.size}) - ${t2 - t0}ms total ")

    val pluginObjectsMaps = plugins.map(p => (p.pluginId, p.objects)).toMap
    val pluginViewObjects = pluginObjectsMaps.flatMap(_._2).groupBy(_.id).map(t => (t._1,t._2.head))
    val pluginUpdateObjects = pluginObjectsMaps.flatMap(_._2).filter(_.refObj.isDefined).map(_.refObj.get).
      groupBy(_.id).map(t => (t._1,t._2.head))

    processForwardObjects(pluginViewObjects)
    val t3 = System.currentTimeMillis()
    log.info(s"SMGConfigParser.getNewConfig: forward objects processed in ${t3 - t2}ms - ${t3 - t0}ms total ")

    val pluginIndexes = plugins.flatMap(p => p.indexes)

    val indexConfsWithChildIds = (indexConfs ++ pluginIndexes).map { oi =>
      SMGConfIndex(
        id = oi.id,
        title = oi.title,
        flt = oi.flt,
        cols = oi.cols,
        rows = oi.rows,
        aggOp = oi.aggOp,
        xRemoteAgg = oi.xRemoteAgg,
        aggGroupBy = oi.aggGroupBy,
        gbParam = oi.gbParam,
        period = oi.period,
        desc = oi.desc,
        parentId = oi.parentId,
        childIds = if (indexMap.contains(oi.id)) {
          indexMap(oi.id).toList
        } else oi.childIds,
        disableHeatmap = oi.disableHeatmap
      )
    }

    SMGConfIndex.buildChildrenSubtree(indexConfsWithChildIds)
    val t4 = System.currentTimeMillis()
    log.info(s"SMGConfigParser.getNewConfig: index tree processed in ${t4 - t3}ms - ${t4 - t0}ms total ")

    //rebuild all preFetches with notifyConfs
    val preFetchesWithNc = preFetches.map { case (id, pfc) =>
      val matching = indexObjectLevelNotifyConfs.filter { case (idx, nconf) =>
        idx.flt.matchesCommand(pfc)
      }
      if (matching.isEmpty)
        (id,pfc)
      else {
        val (src, srcId) = if (pfc.notifyConf.isDefined)
          (SMGMonAlertConfSource.OBJ, pfc.id)
        else (matching.head._2.src, matching.head._2.srcId)
        val allConfs: Seq[SMGMonNotifyConf] = Seq(pfc.notifyConf).flatten ++ matching.map(_._2)
        val allBackoffs = allConfs.flatMap(_.notifyBackoff)
        val allNotifyStrikes = allConfs.flatMap(_.notifyStrikes)
        val mergedNc = SMGMonNotifyConf(
          src = src,
          srcId = srcId,
          crit = allConfs.flatMap(_.crit),
          fail = allConfs.flatMap(_.fail),
          warn = allConfs.flatMap(_.warn),
          anom = allConfs.flatMap(_.anom),
          notifyBackoff = if (allBackoffs.isEmpty) None else Some(allBackoffs.max),
          notifyDisable = allConfs.exists(_.notifyDisable),
          notifyStrikes = if (allNotifyStrikes.isEmpty) None else Some(allNotifyStrikes.min)
        )
        val newPfc = SMGPreFetchCmd(
          id = pfc.id,
          command = pfc.command,
          desc = pfc.desc,
          preFetch = pfc.preFetch,
          ignoreTs = pfc.ignoreTs,
          childConc = pfc.childConc,
          notifyConf = Some(mergedNc),
          passData = pfc.passData,
          delay = pfc.delay
        )
        (id, newPfc)
      }
    }

    val t5 = System.currentTimeMillis()
    log.info(s"SMGConfigParser.getNewConfig: rebuilt pre_fetches with notify confs in ${t5 - t4}ms - ${t5 - t0}ms total ")

    // Process Index alert/notify configs, after all objects and indexes are defined
    // first get all plugin ObjectUpdates - filtering objects which has refObj defined and then using the
    // unique refObjs (._head after grouping by refObj id)
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
            if ((lblAsIx == ix) || (v.label.getOrElse(s"ds$ix") == lbl))
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
            if ((lblAsIx == ix) || (v.label.getOrElse(s"ds$ix") == lbl))
              addNotifyConf(ou.id, ix, ac)
          }
        }
      }
      indexObjectLevelNotifyConfs.foreach { t2 =>
        if (t2._1.flt.matches(ou)) {
          ou.vars.indices.foreach { ix =>
            addNotifyConf(ou.id, ix, t2._2)
          }
        }
      }
    }

    val objectAlertConfs = objectAlertConfMaps.map { t =>
      val oid = t._1
      val m = t._2.map(t => (t._1, t._2.toList)).toMap
      (oid, SMGMonAlertConfObj(m))
    }

    val objectNotifyConfs = objectNotifyConfMaps.map { t =>
      val oid = t._1
      val m = t._2.map(t => (t._1, t._2.toList)).toMap
      (oid, SMGMonNotifyConfObj(m))
    }

    val t6 = System.currentTimeMillis()
    log.info(s"SMGConfigParser.getNewConfig: processed index/object alert/notify confs in ${t6 - t5}ms - ${t6 - t0}ms total ")

    val allViewObjectsConf = allViewObjectIds.flatMap{ oid =>
      allViewObjectsById.get(oid)
    }

    val finalIntervalConfs = intervals.toSeq.map { intvl =>
      (intvl, intervalConfs.getOrElse(intvl, IntervalThreadsConfig.defaultConf(intvl)))
    }.toMap

    val ret = SMGLocalConfig(
      globals = globalConf.toMap,
      confViewObjects = allViewObjectsConf.toList,
      indexes = indexConfsWithChildIds,
      rrdConf = createRrdConf,
      defaultRrdDir = rrdDir,
      rrdDirLevelsDef = levelsDef,
      imgDir = if (globalConf.contains("$img_dir")) globalConf("$img_dir") else imgDir,
      urlPrefix = if (globalConf.contains("$url_prefix")) globalConf("$url_prefix") else urlPrefix,
      intervalConfs = finalIntervalConfs,
      preFetches = preFetchesWithNc.toMap,
      remotes = remotes.toList,
      remoteMasters = remoteMasters.toList,
      pluginObjects = pluginObjectsMaps,
      pluginPreFetches = plugins.map(p => (p.pluginId, p.preFetches)).toMap,
      objectAlertConfs = objectAlertConfs.toMap,
      notifyCommands = notifyCommands.toMap,
      objectNotifyConfs = objectNotifyConfs.toMap,
      hiddenIndexes = hiddenIndexConfs.toMap,
      customDashboards = cDashboardConfigs.toList,
      rraDefs = rraDefs.toMap,
      authUsers = authUsers.toList,
      configErrors = configErrors.toList
    )

    val t7 = System.currentTimeMillis()
    log.info(s"SMGConfigParser.getNewConfig: return object constructed in ${t7 - t6}ms - ${t7 - t0}ms total ")

    ret
  } // getNewConfig
}
