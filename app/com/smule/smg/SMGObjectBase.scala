package com.smule.smg

/**
  * Created by asen on 10/6/16.
  */
trait SMGObjectBase {

  /**
    * object id
    */
  val id: String

  /**
    * object title
    */
  val title: String

  /**
    * List of Maps for each variable of this object
    */
  val vars: List[Map[String, String]]

  /**
    * object update interval
    */
  def interval: Int

  /**
    * rrdtool type (GAUGE, COUNTER etc)
    * @return
    */
  def rrdType: String

  val rraDef: Option[SMGRraDef]

  /**
    * All applicable for search vars definitions (object views can define subsets)
    * @return
    */
  def searchVars : List[Map[String, String]]

  private def searchRemoteIdSeq = if (SMGRemote.isRemoteObj(id)) Seq(SMGRemote.remoteId(id)) else Seq(SMGRemote.localName)

  lazy val searchText: String = (Seq(SMGRemote.localId(id)) ++ searchRemoteIdSeq ++ Seq(title,
    searchVars.map(v => v.getOrElse("label","") + " " + v.getOrElse("mu", "") ).mkString(" ")
  )).mkString(" ").toLowerCase

}
