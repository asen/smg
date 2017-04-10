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

  def fetchValues: List[Double]
  def cachedValues: List[Double]
  def invalidateCachedValues(): Unit

  val preFetch: Option[String]
  val rrdFile: Option[String]
  val rraDef: Option[SMGRraDef]

  val pluginId: Option[String]

  override def parentId: Option[String] =  preFetch //preFetch.map(pfid => SMGMonPfState.stateId(pfid, interval))

  override def searchVars: List[Map[String, String]] = vars

  def notifyConf: Option[SMGMonNotifyConf]

  val rrdInitSource: Option[String] = None

  def inspect: String = List(
    "pluginId" -> pluginId,
    "interval" -> interval,
    "id" -> id,
    "title" -> title,
    "vars" -> vars,
    "rrdType" -> rrdType,
    "preFetch" -> preFetch,
    "rrdFile" -> rrdFile,
    "rraDef" -> rraDef,
    "cachedValues" -> cachedValues
  ).map { case (k,v) => s"$k=$v"}.mkString(", ")
}
