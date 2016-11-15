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
  def fetchValues: List[Double]
  val preFetch: Option[String]
  val rrdFile: Option[String]
  val rraDef: Option[SMGRraDef]

  val pluginId: Option[String]

  override def parentId: Option[String] =  preFetch //preFetch.map(pfid => SMGMonPfState.stateId(pfid, interval))

  override def searchVars: List[Map[String, String]] = vars
}
