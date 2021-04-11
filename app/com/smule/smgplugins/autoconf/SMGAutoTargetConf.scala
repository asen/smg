package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.SMGLoggerApi
import com.smule.smg.notify.SMGMonNotifyConf

import java.io.File
import java.nio.file.Paths
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
    uid.get + ".yml" // XXX this wuld throw on empty uid but our parser prevents that case

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
        nodeName = ymap.get("node_name").map(_.toString),
        command = ymap.get("command").map(_.toString),
        context = if (ymap.contains("context")) yobjMap(ymap("context")).toMap else Map()
      )
    )
  }
}
