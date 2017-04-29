package com.smule.smg

import java.io.File
import java.nio.file.{FileSystems, PathMatcher}
import java.util

import org.yaml.snakeyaml.Yaml
import play.api.Configuration

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.collection.JavaConversions._

/**
  * Helper class to deal with Yaml parsing
  */
class SMGConfigParser(log: SMGLoggerApi) {

  // TODO
  val defaultInterval: Int = 60 // seconds
  val defaultTimeout: Int = 30  // seconds

  def validateOid(oid: String): Boolean = oid.matches("^[\\w\\._-]+$")

  def getListOfFiles(dir: String, matcher: PathMatcher):List[File] = {
    val d = new File(dir)
    if (d.exists && d.isDirectory) {
      d.listFiles.filter{ (f:File) =>
        //        log.info(f.toPath);
        f.isFile && matcher.matches(f.toPath)}.toList.sortBy(f => f.toPath)
    } else {
      log.warn("SMGConfigServiceImpl.getListOfFiles: " + dir + " : glob did not match anything")
      List[File]()
    }
  }

  def expandGlob(glob: String) : List[String] = {
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

  def keyValFromMap(m: java.util.Map[String, Object]): (String,Object) = {
    val firstKey = m.keys.collectFirst[String]{ case x => x }.getOrElse("")
    val retVal = m.remove(firstKey)
    if (retVal != null)
      (firstKey, retVal)
    else
      (firstKey, m)
  }

  def getRrdType(ymap: java.util.Map[String, Object], default: Option[String]): String = {
    val realDefault = default.getOrElse("GAUGE")
    // XXX support for both rrdType (deprecated) and rrd_type syntax
    if (ymap.contains("rrd_type"))
      ymap("rrd_type").toString
    else ymap.getOrElse("rrdType", realDefault).toString
  }

  private def yamlVarsToVars(yamlVars: Object): List[Map[String, String]] = {
    yamlVars.asInstanceOf[util.ArrayList[util.Map[String, Object]]].toList.map(
      (m: util.Map[String, Object]) => m.map { t => (t._1, t._2.toString) }.toMap
    )
  }

  def ymapVars(ymap: java.util.Map[String, Object]): List[Map[String, String]] = {
    if (ymap.contains("vars")) {
      yamlVarsToVars(ymap("vars"))
    } else {
      List()
    }
  }

  def ymapCdefVars(ymap: java.util.Map[String, Object]): List[Map[String, String]] = {
    if (ymap.contains("cdef_vars")) {
      yamlVarsToVars(ymap("cdef_vars"))
    } else {
      List()
    }
  }

  object ForwardObjectRef extends Enumeration {
    type objType = Value
    val GRAPH_OBJ, AGG_OBJ = Value
  }

  case class ForwardObjectRef(oid: String,
                              confFile: String,
                              ymap: java.util.Map[String, Object],
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
    val configErrors = ListBuffer[String]()

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

    def checkFetchCommandNotifyConf(pfId: String, notifyConf: Option[SMGMonNotifyConf], confFile: String): Unit = {
      if (notifyConf.isDefined){
        val nc = notifyConf.get
        val lb = ListBuffer[String]()
        if (nc.spike.nonEmpty) {
          lb += s"spike=${nc.spike.mkString(",")}"
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

    def processPrefetch(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]]
      if (yamlMap.contains("id") && yamlMap.contains("command")) {
        val id = yamlMap.get("id").toString
        if (!checkOid(id)) {
          processConfigError(confFile,
            s"processPrefetch: id already defined (ignoring): $id: " + yamlMap.toString)
        } else {
          val cmd = SMGCmd(yamlMap.get("command").toString, yamlMap.getOrElse("timeout", 30).asInstanceOf[Int]) //  TODO get 30 from a val
          val parentPfStr = yamlMap.getOrElse("pre_fetch", "").toString
          val parentPf = if (parentPfStr == "") None else Some(parentPfStr)
          val ignoreTs = yamlMap.contains("ignorets") && (yamlMap.get("ignorets").toString != "false")
          val childConc = if (yamlMap.contains("child_conc"))
            yamlMap.get("child_conc").asInstanceOf[Int]
          else 1

          val notifyConf = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, id, yamlMap.toMap.map(kv => (kv._1, kv._2.toString)))
          checkFetchCommandNotifyConf(id, notifyConf, confFile)
          preFetches(id) = SMGPreFetchCmd(id, cmd, parentPf, ignoreTs, Math.max(1, childConc), notifyConf)
        }
      } else {
        processConfigError(confFile, "processPrefetch: $pre_fetch yamlMap does not have command and id: " + yamlMap.toString)
      }
    }

    def processNotifyCommand(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]]
      if (yamlMap.contains("id") && yamlMap.contains("command")) {
        val id = yamlMap.get("id").toString
        if (notifyCommands.contains(id)) {
          processConfigError(confFile,
            s"processNotifyCommand: notify command id already defined (ignoring): $id: " + yamlMap.toString)
        } else {
          notifyCommands(id) = SMGMonNotifyCmd(id, yamlMap.get("command").toString, yamlMap.getOrElse("timeout", 30).asInstanceOf[Int])
        }
      } else {
        processConfigError(confFile, "processNotifyCommand: $notify-command yamlMap does not have command and id: " + yamlMap.toString)
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
        processConfigError(confFile, "processRemote: $remote yamlMap does not have id and url: " + yamlMap.toString)
      }
    }

    def processRraDef(t: (String,Object), confFile: String ): Unit = {
      val yamlMap = t._2.asInstanceOf[java.util.Map[String, Object]]
      if (yamlMap.contains("id") && yamlMap.contains("rra")) {
        val rid = yamlMap.get("id").toString
        if (rraDefs.contains(rid)) {
          processConfigError(confFile, "processRraDef: duplicate $rra_def id: " + rid)
        } else {
          rraDefs(rid) = SMGRraDef(rid, yamlMap.get("rra").asInstanceOf[util.ArrayList[String]].toList)
          log.debug("SMGConfigServiceImpl.processRraDef: Added new rraDef: " + rraDefs(rid))
        }
      } else {
        processConfigError(confFile, "processRraDef: $rra_def yamlMap does not have id and rra: " + yamlMap.toString)
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
      globalConf(key) = sval
    }

    def processIndex( t: (String,Object), isHidden: Boolean, confFile: String ): Unit = {
      val idxId = t._1.substring(1)
      if (indexIds.contains(idxId)) {
        processConfigError(confFile, "processIndex: skipping duplicate index with id: " + t._1)
      } else {
        try {
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]]
          val idx = new SMGConfIndex(idxId, ymap)
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
          case x : ClassCastException => processConfigError(confFile, s"processIndex: bad index tuple ($t) ex: $x")
        }
      }
    }

    def checkOid(oid: String): Boolean = validateOid(oid) &&
      (!objectIds.contains(oid)) && (!preFetches.contains(oid))

    def processObjectVarsAlertAndNotifyConfs(ymap: java.util.Map[String, Object], oid: String): List[Map[String,String]] = {
      val myYmapVars = ymapVars(ymap)
      // parse alert confs
      myYmapVars.zipWithIndex.foreach { t =>
        val ix = t._2
        val m = t._1
        val ac = SMGMonVarAlertConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid, m)
        if (ac.isDefined) {
          addAlertConf(oid, ix, ac.get)
        }
      }
      // parse notify confs
      myYmapVars.zipWithIndex.foreach { t =>
        val ix = t._2
        val m = t._1
        val nc = SMGMonNotifyConf.fromVarMap(SMGMonAlertConfSource.OBJ, oid, m)
        if (nc.isDefined) {
          addNotifyConf(oid, ix, nc.get)
        }
      }
      // exclude alert- and notify- defs from the vars maps so it is not passed around remotes and does not mess up aggregation
      val ymapFilteredVars = myYmapVars.map { m =>
        m.filter(t => !(SMGMonVarAlertConf.isAlertKey(t._1) || SMGMonNotifyConf.isNotifyKey(t._1)) )
      }
      ymapFilteredVars
    }

    def getRraDef(confFile: String, oid: String, ymap: java.util.Map[String, Object]): Option[SMGRraDef] = {
      if (ymap.contains("rra")) {
        val rid = ymap.get("rra").toString
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
      if (allViewObjectsById.contains(k)){
        Some(allViewObjectsById(k))
      } else pluginObjs.get(k)
    }

    def createGraphObject(oid: String,
                          ymap: java.util.Map[String, Object],
                          confFile: String,
                          pluginViewObjs: Map[String, SMGObjectView]
                         ): Unit = {
      try {
        val refid = ymap.get("ref").toString
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
            gvIxes = ymap.getOrElse("gv", new util.ArrayList[Int]()).asInstanceOf[util.ArrayList[Int]].toList,
            rrdFile = refobj.rrdFile,
            refObj = refobj.refObj,
            rrdType = refobj.rrdType)
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
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]]
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

    def createAggObject(oid: String,
                        ymap: java.util.Map[String, Object],
                        confFile: String,
                        pluginViewObjs: Map[String, SMGObjectView]
                       ): Unit = {
      try {
        val confOp = ymap.getOrDefault("op", "SUM")
        val op = confOp match {
          case "SUMN" => "SUMN"
          case "AVG" => "AVG"
          case "SUM" => "SUM"
          case x => {
            processConfigError(confFile,
              s"processAggObject: unsupported agg op for $oid: $x (assuming SUM)", isWarn = true)
            "SUM"
          }
        }
        if (ymap.contains("ids")) {
          val ids = ymap("ids").asInstanceOf[util.ArrayList[String]].toList
          val objOpts = ids.map { ovid =>
            val ret = lookupObjectView(ovid, pluginViewObjs).flatMap(_.refObj)
            if (ret.isEmpty) {
              processConfigError(confFile, "processAggObject: agg object references " +
                s"undefined object: $oid, ref id=$ovid (agg object will be ignored)")
            }
            ret
          }
          if (objOpts.nonEmpty && objOpts.forall(_.isDefined)) {
            val objs = objOpts.map(_.get)
            val myRraDef = getRraDef(confFile, oid, ymap)
            val myVars = if (ymap.containsKey("vars")) {
              processObjectVarsAlertAndNotifyConfs(ymap, oid)
            } else {
              // XXX no vars defined, use first object's ones but filter out the "max" value
              // which is likely wrong for the SUM object.
              objs.head.vars.map { v => v.filter { case (k, vv) => k != "max" } }
            }
            val myRrdType = getRrdType(ymap, Some(objs.head.rrdType))
            // sanity check the objects, all must have at least myVars.size vars
            // TODO: more thorough validation?
            if (!objs.exists { ou =>
              val ret = ou.vars.size < myVars.size
              if (ret) {
                processConfigError(confFile, "processAggObject: agg object references " +
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
                stack = ymap.getOrElse("stack", false).asInstanceOf[Boolean],
                rrdFile = Some(rrdDir + "/" + oid + ".rrd"),
                rraDef = myRraDef,
                rrdInitSource = if (ymap.contains("rrd_init_source")) Some(ymap.get("rrd_init_source").toString) else None,
                notifyConf = notifyConf
              )
              objectUpdateIds(oid) = rrdAggObj
              allViewObjectsById(rrdAggObj.id) = rrdAggObj
            } // else - error already logged (checking for incompatible vars)
          } // else - errors already logged (checking for invalid object refs)
        } else {
          processConfigError(confFile,
            s"processAggObject: agg object definition without ids: $oid, ignoring")
        }
      } catch {
        case t: Throwable => processConfigError(confFile,
          s"createGraphObject: unexpected error creating Aggregate object - oid=$oid")
      }
    }

    def processAggObject( t: (String,Object), confFile: String ): Unit = {
      val oid =  t._1.substring(1) // strip the +
      if (!checkOid(oid)){
        processConfigError(confFile, "processAggObject: skipping agg object with invalid or duplicate id: " + oid)
      } else {
        try {
          // only record the id/position and yaml for later
          val ymap = t._2.asInstanceOf[java.util.Map[String, Object]]
          objectIds += oid
          allViewObjectIds += oid
          forwardObjects += ForwardObjectRef(oid, confFile, ymap, ForwardObjectRef.AGG_OBJ)
        } catch {
          case x : ClassCastException => processConfigError(confFile,
            s"processAggObject: bad object tuple ($t) ex: $x")
        }
      }
    }

    def parseConf(confFile: String): Unit = {
      val t0 = System.currentTimeMillis()
      log.debug("SMGConfigServiceImpl.parseConf(" + confFile + "): Starting at " + t0)
      try {
        val confTxt = Source.fromFile(confFile).mkString
        val yaml = new Yaml()
        val yamlTopObject = yaml.load(confTxt)
        try {
          yamlTopObject.asInstanceOf[java.util.List[Object]].foreach { yamlObj: Object =>
            if (yamlObj == null) {
              processConfigError(confFile, "parseConf: Received null yamlObj")
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
              case x: ClassCastException => processConfigError(confFile,
                s"parseConf: bad yaml object - wrong type ($yamlObj) ex: $x")
            }
          } //foreach
        } catch {
          case e: ClassCastException => processConfigError(confFile, "bad top level object (expected List): " + yamlTopObject.getClass.toString)
        }
        val t1 = System.currentTimeMillis()
        log.debug("SMGConfigServiceImpl.parseConf(" + confFile + "): Finishing for " + (t1 - t0) + " milliseconds at " + t1)
      } catch {
        case e: Throwable => processConfigError(confFile, s"parseConf: unexpected exception: $e")
      }
    } // def parseConf

    def createRrdConf = {
      SMGRrdConfig(
        if (globalConf.contains("$rrd_tool")) globalConf("$rrd_tool") else rrdTool,
        if (globalConf.contains("$rrd_socket")) Some(globalConf("$rrd_socket")) else None,
        if (globalConf.contains("$rrd_graph_width")) globalConf("$rrd_graph_width").toInt else 539,
        if (globalConf.contains("$rrd_graph_height")) globalConf("$rrd_graph_height").toInt else 135,
        globalConf.get("$rrd_graph_font")
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

    parseConf(topLevelConfigFile)
    reloadPluginsConf()

    val pluginObjectsMaps = plugins.map(p => (p.pluginId, p.objects)).toMap
    val pluginViewObjects = pluginObjectsMaps.flatMap(_._2).groupBy(_.id).map(t => (t._1,t._2.head))
    val pluginUpdateObjects = pluginObjectsMaps.flatMap(_._2).filter(_.refObj.isDefined).map(_.refObj.get).
      groupBy(_.id).map(t => (t._1,t._2.head))

    processForwardObjects(pluginViewObjects)

    val pluginIndexes = plugins.flatMap(p => p.indexes)

    val indexConfsWithChildIds = (indexConfs ++ pluginIndexes).map { oi =>
      SMGConfIndex(
        id = oi.id,
        title = oi.title,
        flt = oi.flt,
        cols = oi.cols,
        rows = oi.rows,
        aggOp = oi.aggOp,
        xAgg = oi.xAgg,
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

    val allViewObjectsConf = allViewObjectIds.filter{ oid =>
      allViewObjectsById.contains(oid)
    }.map(oid => allViewObjectsById(oid))

    val ret = SMGLocalConfig(
      globals = globalConf.toMap,
      confViewObjects = allViewObjectsConf.toList,
      indexes = indexConfsWithChildIds,
      rrdConf = createRrdConf,
      imgDir = if (globalConf.contains("$img_dir")) globalConf("$img_dir") else imgDir,
      urlPrefix = if (globalConf.contains("$url_prefix")) globalConf("$url_prefix") else urlPrefix,
      intervals = intervals.toSet,
      preFetches = preFetches.toMap,
      remotes = remotes.toList,
      remoteMasters = remoteMasters.toList,
      pluginObjects = pluginObjectsMaps,
      pluginPreFetches = plugins.map(p => (p.pluginId, p.preFetches)).toMap,
      objectAlertConfs = objectAlertConfs.toMap,
      notifyCommands = notifyCommands.toMap,
      objectNotifyConfs = objectNotifyConfs.toMap,
      hiddenIndexes = hiddenIndexConfs.toMap,
      configErrors = configErrors.toList
    )
    ret
  } // getNewConfig
}
