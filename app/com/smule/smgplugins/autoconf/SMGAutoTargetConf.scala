package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.SMGLoggerApi
import com.smule.smg.notify.SMGMonNotifyConf

import java.io.File
import java.nio.file.Paths
import java.util
import scala.collection.mutable

case class SMGAutoTargetConf(
                              template: String,
                              output: Option[String],
                              uid: Option[String],
                              // data and nodeHost can be resolved at runtime
                              // by running the fetchCommand for data
                              // and DNS lookup for nodeName
                              runtimeData: Boolean,
                              runtimeDataTimeoutSec: Option[Int],
                              resolveName: Boolean,
                              //minimal delay between conf re-gen.
                              regenDelay: Option[Int],
                              // the two special attributes
                              nodeName: Option[String],
                              command: Option[String],
                              context: Map[String,Object]
                            ) {
  lazy val staticMap: Map[String,Object] = Map(
    "node_name" -> nodeName.getOrElse(""),
    "command" -> command.getOrElse("")
  ) ++ context

  val confOutput: String = if (output.isDefined)
    output.get
  else
    uid.get + ".yml" // XXX this would throw on empty uid but our parser prevents that case

  def confOutputFile(confDir: Option[String]): String = {
    if (confDir.isDefined && !Paths.get(confOutput).isAbsolute){
      confDir.get.stripSuffix(File.separator) + File.separator + confOutput
    } else confOutput
  }

  def inspect: String = s"template=$template output=${output.getOrElse("None")} uid=${uid.getOrElse("None")} " +
    s"nodeName=${nodeName.getOrElse("None")} command=${uid.getOrElse("None")} " +
    s"runtimeData=$runtimeData resolveName=$resolveName context=${context.toString()}"
}

object SMGAutoTargetConf {

  def dumpYamlList(lst: Seq[Object]): java.util.List[Object] = {
    val ret = new java.util.ArrayList[Object]()
    lst.foreach { (v) =>
      val toPut: Object = v match {
        case m: Map[String, Object] @unchecked => dumpYamlMap(m)
        case s: Seq[Object] @unchecked => dumpYamlList(s)
        case _ => v
      }
      ret.add(toPut)
    }
    ret
  }

  def dumpYamlMap(context: Map[String, Object]): java.util.Map[String,Object] = {
    val ret = new java.util.HashMap[String,Object]()
    context.foreach { case (k,v) =>
      val toPut: Object = v match {
        case m: Map[String, Object] @unchecked => dumpYamlMap(m)
        case s: Seq[Object] @unchecked => dumpYamlList(s)
        case _ => v
      }
      ret.put(k, toPut)
    }
    ret
  }

  def dumpYamlObj(in: SMGAutoTargetConf): java.util.Map[String,Object] = {
    val ret = new java.util.HashMap[String,Object]()
    ret.put("template", in.template)
    if (in.output.isDefined)
      ret.put("output", in.output.get)
    if (in.uid.isDefined)
      ret.put("uid", in.uid.get)
    if (in.runtimeData)
      ret.put("runtime_data", Boolean.box(true))
    if (in.runtimeDataTimeoutSec.isDefined)
      ret.put("runtime_data_timeout_sec", Integer.valueOf(in.runtimeDataTimeoutSec.get))
    if (in.resolveName)
      ret.put("resolve_name", Boolean.box(true))
    if (in.nodeName.isDefined)
      ret.put("node_name", in.nodeName.get)
    if (in.command.isDefined)
      ret.put("command", in.command.get)
    if (in.context.nonEmpty)
      ret.put("context", dumpYamlMap(in.context))
    ret
  }

  def fromYamlObj(ymap: mutable.Map[String,Object], log: SMGLoggerApi): Option[SMGAutoTargetConf] = {
    if (!ymap.contains("template")){
      log.error("SMGAutoTargetConf: Config does not contain template param")
      return None
    }
    if (!ymap.contains("uid") && !ymap.contains("output")){
      log.error("SMGAutoTargetConf: Config must contain at least one of uid or output params")
      return None
    }
    val runtimeData = ymap.contains("runtime_data") && ymap("runtime_data").toString == "true"
    if (runtimeData && !ymap.contains("command")){
      log.error("SMGAutoTargetConf: Config must contain command param if runtime_data is enabled")
      return None
    }
    val resolveName = ymap.contains("resolve_name") && ymap("resolve_name").toString == "true"
    if (resolveName && !ymap.contains("node_name")){
      log.error("SMGAutoTargetConf: Config must contain node_name param if resolve_name is enabled")
      return None
    }
    Some(
      SMGAutoTargetConf(
        template = ymap("template").toString,
        output = ymap.get("output").map(_.toString),
        uid = ymap.get("uid").map(_.toString),
        runtimeData = runtimeData,
        runtimeDataTimeoutSec = ymap.get("runtime_data_timeout_sec").map(_.asInstanceOf[Int]),
        resolveName = ymap.contains("resolve_name") && ymap("resolve_name").toString == "true",
        regenDelay = ymap.get("regen_delay").map(_.asInstanceOf[Int]),
        nodeName = ymap.get("node_name").map(_.toString),
        command = ymap.get("command").map(_.toString),
        context = if (ymap.contains("context")) yobjMap(ymap("context")).toMap else Map()
      )
    )
  }
}
