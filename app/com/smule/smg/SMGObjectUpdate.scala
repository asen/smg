package com.smule.smg

/**
  * Created by asen on 12/3/15.
  */
trait SMGObjectUpdate extends SMGObjectBase {
//  override val id: String
//  override val title: String
//  override val vars: List[Map[String, String]]

  override val interval: Int
  val rrdType: String
  def fetchValues: List[Double]
  val preFetch: Option[String]
  val rrdFile: Option[String]
  val rraDef: Option[SMGRraDef]

  override def searchVars: List[Map[String, String]] = vars
}
