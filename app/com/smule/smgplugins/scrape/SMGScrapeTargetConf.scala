package com.smule.smgplugins.scrape

import java.io.File
import java.nio.file.Paths
import java.util

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.config.SMGConfigParser.{yobjList, yobjMap}
import com.smule.smg.core.SMGFilter
import com.smule.smg.monitor.{SMGMonAlertConfSource, SMGMonNotifyConf}

import scala.collection.JavaConverters._
import scala.collection.mutable

case class SMGScrapeTargetConf(
                                uid: String,
                                humanName: String,
                                command: String,
                                timeoutSec: Int,
                                confOutput: String,
                                confOutputBackupExt: Option[String],
                                filter: Option[SMGFilter],
                                interval: Int,
                                parentPfId: Option[String],
                                parentIndexId: Option[String],
                                idPrefix: Option[String],
                                notifyConf: Option[SMGMonNotifyConf],
                                regexReplaces: Seq[RegexReplaceConf],
                                labelsInUids: Boolean,
                                extraLabels: Map[String,String],
                                rraDefAgg: Option[String],
                                rraDefDtl: Option[String],
                                needParse: Boolean
                              ) {
   lazy val inspect: String = s"uid=$uid humanName=$humanName interval=$interval command=$command " +
     s"timeout=$timeoutSec confOutput=$confOutput parentPfId=$parentPfId labelsInUids=$labelsInUids " +
     s"filter: ${filter.map(_.humanText).getOrElse("None")}"

  def confOutputFile(confDir: Option[String]): String = {
    if (confDir.isDefined && !Paths.get(confOutput).isAbsolute){
      confDir.get.stripSuffix(File.separator) + File.separator + confOutput
    } else confOutput
  }
}

object SMGScrapeTargetConf {

  def dumpYamlObj(in: SMGScrapeTargetConf): java.util.Map[String,Object] = {
    val ret = new java.util.HashMap[String,Object]()
    ret.put("uid", in.uid)
    ret.put("command", in.command)
    ret.put("conf_output", in.confOutput)
    if (in.uid != in.humanName)
      ret.put("name", in.humanName)
    if (in.timeoutSec != SMGConfigParser.defaultTimeout)
      ret.put("timeout", Integer.valueOf(in.timeoutSec))
    if (in.confOutputBackupExt.isDefined)
      ret.put("conf_output_backup_ext", in.confOutputBackupExt.get)
    if (in.filter.isDefined){
      val fltObj = SMGYamlConfigGen.filterToYamlMap(in.filter.get)
      ret.put("filter", fltObj)
    }
    if (in.interval != SMGConfigParser.defaultInterval)
      ret.put("interval", Integer.valueOf(in.interval))
    if (in.parentPfId.isDefined)
      ret.put("pre_fetch", in.parentPfId.get)
    if (in.parentIndexId.isDefined)
      ret.put("parent_index", in.parentIndexId.get)
    if (in.idPrefix.isDefined)
      ret.put("id_prefix", in.idPrefix.get)
    if (in.notifyConf.isDefined){
      val nc = SMGYamlConfigGen.notifyConfToYamlMap(in.notifyConf.get).asScala
      nc.foreach { case (nk, nv) => ret.put(nk,nv) }
    }
    if (in.regexReplaces.nonEmpty){
      val replaces = new util.ArrayList[Object]()
      in.regexReplaces.foreach { rr =>
        replaces.add(RegexReplaceConf.toYamlObject(rr))
      }
      ret.put("regex_replaces", replaces)
    }
    if (in.labelsInUids)
      ret.put("labels_in_uids", Boolean.box(true))
    if (in.extraLabels.nonEmpty){
      val elMap = new util.HashMap[String,Object]()
      in.extraLabels.foreach { case (k,v) => elMap.put(k,v)}
      ret.put("extra_labels", elMap)
    }
    if (in.rraDefAgg.isDefined){
      ret.put("rra_agg", in.rraDefAgg.get)
    }
    if (in.rraDefDtl.isDefined){
      ret.put("rra_dtl", in.rraDefDtl.get)
    }
    if (!in.needParse)
      ret.put("need_parse", "false")
    ret
  }

  def fromYamlObj(ymap: mutable.Map[String,Object]): Option[SMGScrapeTargetConf] = {
    if (!ymap.contains("uid")){
      return None
    }
    if (!ymap.contains("command")){
      return None
    }
    if (!ymap.contains("conf_output")){
      return None
    }
    val uid = ymap("uid").toString
    val notifyConf = SMGMonNotifyConf.fromVarMap(
      // first two technically unused
      SMGMonAlertConfSource.OBJ,
     "scrape-target." + uid,
      ymap.toMap.map(t => (t._1, t._2.toString))
    )
    Some(
      SMGScrapeTargetConf(
        uid = uid,
        humanName = if (ymap.contains("name")) ymap("name").toString else ymap("uid").toString,
        command = ymap("command").toString,
        timeoutSec = ymap.get("timeout").map(_.asInstanceOf[Int]).getOrElse(SMGConfigParser.defaultTimeout),
        confOutput = ymap("conf_output").toString,
        confOutputBackupExt = ymap.get("conf_output_backup_ext").map(_.toString),
        filter = if (ymap.contains("filter")){
          Some(SMGFilter.fromYamlMap(yobjMap(ymap("filter")).toMap))
        } else None,
        interval = ymap.get("interval").map(_.asInstanceOf[Int]).getOrElse(SMGConfigParser.defaultInterval),
        parentPfId  = ymap.get("pre_fetch").map(_.toString),
        parentIndexId = ymap.get("parent_index").map(_.toString),
        idPrefix = ymap.get("id_prefix").map(_.toString),
        notifyConf = notifyConf,
        regexReplaces = ymap.get("regex_replaces").map { yo: Object =>
          yobjList(yo).flatMap { o =>  RegexReplaceConf.fromYamlObject(yobjMap(o)) }
        }.getOrElse(Seq()),
        labelsInUids = ymap.getOrElse("labels_in_uids", "false").toString == "true",
        extraLabels = ymap.get("extra_labels").map{ o: Object =>
          yobjMap(o).map{case (k,v) => (k,v.toString)}.toMap
        }.getOrElse(Map[String,String]()),
        rraDefAgg = ymap.get("rra_agg").map(_.toString),
        rraDefDtl = ymap.get("rra_dtl").map(_.toString),
        needParse = ymap.getOrElse("need_parse", "true").toString != "false"
      )
    )
  }
}
