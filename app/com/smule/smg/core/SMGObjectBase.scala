package com.smule.smg.core

import com.smule.smg.remote.SMGRemote
import com.smule.smg.rrd.SMGRraDef

/**
  * Created by asen on 10/6/16.
  */
trait SMGObjectBase {

  /**
    * object id
    */
  val id: String

  //all prent/pre-fetch ids
  val parentIds: Seq[String]

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
  val rraCfs: Seq[String] = Seq("AVERAGE") // TODO this should be configurable

  val labels: Map[String, String]

  /**
    * All applicable for search vars definitions (object views can define subsets)
    * @return
    */
  def searchVars : List[Map[String, String]]

  private def searchRemoteIdSeq: Seq[String] =
    if (SMGRemote.isRemoteObj(id)) Seq(SMGRemote.remoteId(id)) else Seq(SMGRemote.localName)

  def searchText: String = (
    Seq(SMGRemote.localId(id)) ++ searchRemoteIdSeq ++ parentIds ++ Seq(title,
      searchVars.map(v => v.getOrElse("label","") + " " + v.getOrElse("mu", "") ).mkString(" ")
      ) ++ labels.keys.toSeq.sorted.map{k => s"$k=${labels(k)}"}
    ).mkString(" ").toLowerCase

}
