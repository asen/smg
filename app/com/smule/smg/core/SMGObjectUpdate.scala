package com.smule.smg.core

import com.smule.smg.config.SMGConfigService
import com.smule.smg.monitor.SMGState
import com.smule.smg.notify.SMGMonNotifyConf
import com.smule.smg.rrd.SMGRrd

/**
  * Created by asen on 12/3/15.
  * The SMG "update object" interface, normally an update object maps to a rrd file. It can act
  * as a tree node in the commands/run trees (normally as a leaf, with parents being SMGFetchCommands)
  */
trait SMGObjectUpdate extends SMGObjectView with SMGTreeNode {

//  override val id: String
//  override val title: String
//  override val vars: List[Map[String, String]]
//  override val interval: Int
//  val rrdType: String
//  override def searchVars: List[Map[String, String]] = vars

//  //SMGObjectView props
//  override val stack: Boolean = false
//  override val graphVarsIndexes: Seq[Int] = vars.indices.toList
//  override val cdefVars: List[Map[String, String]] = List()

  override val refObj: Option[SMGObjectUpdate] = Some(this)

  def isCounter: Boolean = (rrdType != "GAUGE") && (rrdType != "ABSOLUTE")

  val preFetch: Option[String]

  val pluginId: Option[String]

  override def parentId: Option[String] =  preFetch //preFetch.map(pfid => SMGMonInternalPfState.stateId(pfid, interval))

  def notifyConf: Option[SMGMonNotifyConf]

  val dataDelay: Int //= 0
  val rrdInitSource: Option[String] //= None

  def numFmt(num: Double, vix: Int, applyCdef: Boolean = true): String = {
    val myNum = if (applyCdef)
      vars(vix).cdef.map(cdf => SMGRrd.computeCdef(cdf, num)).getOrElse(num)
    else
      num
    SMGState.numFmt(myNum, vars(vix).mu)
  }

  def inspect(cfSvc: SMGConfigService): String = List(
    "pluginId" -> pluginId,
    "interval" -> interval,
    "id" -> id,
    "title" -> title,
    "vars" -> vars,
    "rrdType" -> rrdType,
    "preFetch" -> preFetch,
    "rrdFile" -> rrdFile,
    "rraDef" -> rraDef,
    "notifyConf" -> notifyConf.map(nc => s"(${nc.inspect})").getOrElse("None"),
    "cachedValues" -> cfSvc.getCachedValues(this, true)
  ).map { case (k,v) => s"$k=$v"}.mkString(", ")
}
