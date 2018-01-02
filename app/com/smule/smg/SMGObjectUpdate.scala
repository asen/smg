package com.smule.smg

/**
  * Created by asen on 12/3/15.
  */
trait SMGObjectUpdate extends SMGObjectBase with SMGTreeNode {
//  override val id: String
//  override val title: String
//  override val vars: List[Map[String, String]]

  override val interval: Int

  val rrdType: String
  def isCounter: Boolean = (rrdType != "GAUGE") && (rrdType != "ABSOLUTE")

  val preFetch: Option[String]
  val rrdFile: Option[String]
  val rraDef: Option[SMGRraDef]

  val pluginId: Option[String]

  override def parentId: Option[String] =  preFetch //preFetch.map(pfid => SMGMonPfState.stateId(pfid, interval))

  override def searchVars: List[Map[String, String]] = vars

  def notifyConf: Option[SMGMonNotifyConf]

  val dataDelay: Int = 0
  val rrdInitSource: Option[String] = None

  def numFmt(num: Double, vix: Int): String = {
    val myNum = vars(vix).get("cdef").map(cdf => SMGRrd.computeCdef(cdf, num)).getOrElse(num)
    SMGState.numFmt(myNum, vars(vix).get("mu"))
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
    "cachedValues" -> cfSvc.getCachedValues(this)
  ).map { case (k,v) => s"$k=$v"}.mkString(", ")
}
