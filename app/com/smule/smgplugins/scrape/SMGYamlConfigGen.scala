package com.smule.smgplugins.scrape

import java.util

import com.smule.smg.config.SMGConfIndex
import com.smule.smg.core.{SMGFilter, SMGPreFetchCmd, SMGRrdAggObject, SMGRrdObject}
import com.smule.smg.monitor.SMGMonNotifyConf
import org.yaml.snakeyaml.Yaml

import scala.collection.JavaConverters._


// sync this with SMGConfigParser
class SMGYamlConfigGen() {

  def yamlObjToStr(in: Object): String = {
    val yaml = new Yaml()
    yaml.dump(in) + "\n"
  }

  private def smgObjectsToYamlList[T](objs: Seq[T], yamlConvert: (T) => Object): util.List[Object] = {
    val ret = new util.ArrayList[Object]()
    objs.foreach(o => ret.add(yamlConvert(o)))
    ret
  }

  def rrdObjectsToYamlList(objs: Seq[SMGRrdObject]): util.List[Object] = {
    smgObjectsToYamlList(objs, rrdObjectToYamlObj)
  }

  def rrdAggObjectsToYamlList(objs: Seq[SMGRrdAggObject]): util.List[Object] = {
    smgObjectsToYamlList(objs, rrdAggObjectToYamlObj)
  }

  def preFetchesToYamlList(objs: Seq[SMGPreFetchCmd]): util.List[Object] = {
    smgObjectsToYamlList(objs, preFetchToYamlObj)
  }

  def confIndexesToYamlList(objs: Seq[SMGConfIndex]): util.List[Object] = {
    smgObjectsToYamlList(objs, confIndexToYamlObj)
  }

  private def notifyConfToYamlMap(obj: SMGMonNotifyConf): util.Map[String, Object] =  {
    val ret = new util.HashMap[String, Object]()
    if (obj.crit.nonEmpty)
      ret.put("notify-crit", obj.crit.mkString(","))
    if (obj.unkn.nonEmpty)
      ret.put("notify-unkn", obj.unkn.mkString(","))
    if (obj.warn.nonEmpty)
      ret.put("notify-warn", obj.warn.mkString(","))
    if (obj.spike.nonEmpty)
      ret.put("notify-anom", obj.spike.mkString(","))
    if (obj.notifyBackoff.nonEmpty)
      ret.put("notify-backoff", Integer.valueOf(obj.notifyBackoff.get))
    if (obj.notifyDisable)
      ret.put("notify-disable", Boolean.box(obj.notifyDisable))
    if (obj.notifyStrikes.isDefined)
      ret.put("notify-strikes", Integer.valueOf(obj.notifyStrikes.get))     
    ret
  }

  private def preFetchToYamlObj(obj: SMGPreFetchCmd): Object = {
    val ret = new util.HashMap[String, Object]()
    ret.put("type", "pre_fetch")
    ret.put("id", obj.id)
    ret.put("command", obj.command.str)
    ret.put("timeout", Integer.valueOf(obj.command.timeoutSec))
    if (obj.preFetch.isDefined)
      ret.put("pre_fetch", obj.preFetch.get)
    if (obj.ignoreTs)
      ret.put("ignorets", Boolean.box(true))
    if (obj.childConc != 1)
      ret.put("child_conc", Integer.valueOf(obj.childConc))
    if (obj.passData)
      ret.put("pass_data", Boolean.box(true))
    if (obj.notifyConf.isDefined){
      val notifyConf = notifyConfToYamlMap(obj.notifyConf.get)
      notifyConf.asScala.foreach { case (k,v) => ret.put(k,v) }
    }
    ret
  }

  private def rrdObjectToYamlObj(obj: SMGRrdObject): Object = {
    val ret = new util.HashMap[String, Object]()
//    ret.put("type", "auto")
    ret.put("id", obj.id)
    //    if (parentIds.nonEmpty) {
    //      val pidsLst = new util.ArrayList[String]()
    //      parentIds.foreach(p => pidsLst.add(p))
    //      ret.put("parentIds", pidsLst)
    //    }
    ret.put("command", obj.command.str)
    ret.put("timeout", Integer.valueOf(obj.command.timeoutSec))
    val varsLst = new util.ArrayList[Object]()
    obj.vars.foreach { vmap =>
      val jmap = new util.HashMap[String, Object]()
      vmap.foreach { t =>
        jmap.put(t._1, t._2)
      }
      varsLst.add(jmap)
    }
    ret.put("vars", varsLst)
    ret.put("title", obj.title)
    ret.put("rrd_type", obj.rrdType)
    ret.put("interval", Integer.valueOf(obj.interval))
    if (obj.dataDelay > 0)
      ret.put("dataDelay", Integer.valueOf(obj.dataDelay))
    if (obj.stack){
      ret.put("stack", "true")
    }
    if (obj.preFetch.isDefined)
      ret.put("pre_fetch", obj.preFetch.get)
    //    if (obj.rrdFile.isDefined)
    //      ret.put("rrdFile", obj.rrdFile.get)
    if (obj.rraDef.isDefined){
      ret.put("rra", obj.rraDef.get.rraId)
    }
    if (obj.rrdInitSource.isDefined){
      ret.put("rrd_init_source", obj.rrdInitSource.get)
    }
    if (obj.notifyConf.isDefined){
      val notifyConf = notifyConfToYamlMap(obj.notifyConf.get)
      notifyConf.asScala.foreach { case (k,v) => ret.put(k,v) }
    }
    if (obj.labels.nonEmpty) {
      val jlabels = new util.HashMap[String, Object]()
      obj.labels.foreach { t =>
        jlabels.put(t._1, t._2)
      }
      ret.put("labels", jlabels)
    }
    ret
  }

  private def rrdAggObjectToYamlObj(obj: SMGRrdAggObject): Object = {
    val ret = new util.HashMap[String, Object]()
    //    ret.put("type", "auto")
    ret.put("id", "+" + obj.id)
    ret.put("op", obj.aggOp)
    val idsLst = new util.ArrayList[Object]()
    obj.ous.foreach { ou =>
      idsLst.add(ou.id)
    }
    ret.put("ids", idsLst)
    val varsLst = new util.ArrayList[Object]()
    obj.vars.foreach { vmap =>
      val jmap = new util.HashMap[String, Object]()
      vmap.foreach { t =>
        jmap.put(t._1, t._2)
      }
      varsLst.add(jmap)
    }
    if (!varsLst.isEmpty)
      ret.put("vars", varsLst)
    ret.put("title", obj.title)
    ret.put("rrd_type", obj.rrdType)
    ret.put("inteval", Integer.valueOf(obj.interval))
    if (obj.dataDelay > 0)
      ret.put("dataDelay", Integer.valueOf(obj.dataDelay))
    if (obj.stack)
      ret.put("stack", Boolean.box(obj.stack))
    //    if (obj.rrdFile.isDefined)
    //      ret.put("rrdFile", obj.rrdFile.get)
    if (obj.rraDef.isDefined)
      ret.put("rra", obj.rraDef.get.rraId)
    if (obj.rrdInitSource.isDefined)
      ret.put("rrd_init_source", obj.rrdInitSource.get)
    if (obj.notifyConf.isDefined){
      val notifyConf = notifyConfToYamlMap(obj.notifyConf.get)
      notifyConf.asScala.foreach { case (k,v) => ret.put(k,v) }
    }
    if (obj.labels.nonEmpty) {
      val jlabels = new util.HashMap[String, Object]()
      obj.labels.foreach { t =>
        jlabels.put(t._1, t._2)
      }
      ret.put("labels", jlabels)
    }
    ret
  }

  private def filterToYamlMap(obj: SMGFilter): util.Map[String, Object] = {
    val ret = new util.HashMap[String, Object]()
    if (obj.px.isDefined)
      ret.put("px", obj.px.get)
    if (obj.sx.isDefined)
      ret.put("sx", obj.sx.get)
    if (obj.rx.isDefined)
      ret.put("rx", obj.rx.get)
    if (obj.rxx.isDefined)
      ret.put("rxx", obj.rxx.get)
    if (obj.prx.isDefined)
      ret.put("prx", obj.prx.get)
    if (obj.trx.isDefined)
      ret.put("trx", obj.trx.get)
    if (obj.remotes.nonEmpty)
      ret.put("remote", obj.remotes.mkString(","))
    // Graph options
    if (obj.gopts.step.isDefined)
      ret.put("step", Integer.valueOf(obj.gopts.step.get))
    if (obj.gopts.pl.isDefined)
      ret.put("pl", obj.gopts.pl.get)
    if (obj.gopts.xsort.isDefined)
      ret.put("xsort", Integer.valueOf(obj.gopts.xsort.get))
    if (obj.gopts.disablePop)
      ret.put("dpp", Boolean.box(obj.gopts.disablePop))
    if (obj.gopts.disable95pRule)
      ret.put("d95p", Boolean.box(obj.gopts.disable95pRule))
    if (obj.gopts.maxY.isDefined)
      ret.put("maxy", Double.box(obj.gopts.maxY.get))
    if (obj.gopts.minY.isDefined)
      ret.put("miny", Double.box(obj.gopts.minY.get))
    if (obj.gopts.logY)
      ret.put("logy", Boolean.box(obj.gopts.logY))
    ret
  }

  private def confIndexToYamlObj(obj: SMGConfIndex): Object = {
    val ret = new util.HashMap[String, Object]()
//    ret.put("type", "auto")
    ret.put("id", "^" + obj.id)
    ret.put("title", obj.title)
    val fltMap = filterToYamlMap(obj.flt)
    fltMap.asScala.foreach { case (k,v) => ret.put(k,v) }
    if (obj.cols.isDefined)
      ret.put("cols", Integer.valueOf(obj.cols.get))
    if (obj.rows.isDefined)
      ret.put("rows", Integer.valueOf(obj.rows.get))
    if (obj.aggOp.isDefined)
      ret.put("agg_op", obj.aggOp.get)
    if (obj.xRemoteAgg)
      ret.put("xagg", Boolean.box(obj.xRemoteAgg))
    if (obj.aggGroupBy.isDefined)
      ret.put("gb", obj.aggGroupBy.get.toString)
    if (obj.period.isDefined)
      ret.put("period", obj.period.get)
    if (obj.desc.isDefined)
      ret.put("desc", obj.desc.get)
    if (obj.parentId.isDefined)
      ret.put("parent", obj.parentId.get)
    if (obj.childIds.nonEmpty){
      val clst = new util.ArrayList[Object]()
      obj.childIds.foreach(clst.add)
      ret.put("children", clst)
    }
    if (obj.disableHeatmap)
      ret.put("dhmap", Boolean.box(obj.disableHeatmap))
    ret
  }
}
