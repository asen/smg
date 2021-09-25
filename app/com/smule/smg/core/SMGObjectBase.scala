package com.smule.smg.core

import com.smule.smg.remote.SMGRemote
import com.smule.smg.rrd.{SMGRraDef, SMGRrd}

import scala.util.Try

/**
  * Created by asen on 10/6/16.
  * The base interface for any SMG object - has the common properties needed to
  * be able to search and filter objects.
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
  val vars: List[SMGObjectVar]

  /**
    * object update interval
    */
  def interval: Int

  def intervalHuman: String = SMGRrd.intervalToStr(interval)

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
  def searchVars : List[SMGObjectVar]

  private def searchRemoteIdSeq: Seq[String] =
    if (SMGRemote.isRemoteObj(id)) Seq(SMGRemote.remoteId(id)) else Seq(SMGRemote.localName)

  def searchText: String = (
    Seq(SMGRemote.localId(id)) ++ searchRemoteIdSeq ++ parentIds ++ Seq(title,
      searchVars.map(v => v.label.getOrElse("") + " " + v.mu.getOrElse("") ).mkString(" ")
      ) ++ labels.keys.toSeq.sorted.map{k => s"$k=${labels(k)}"}
    ).mkString(" ").toLowerCase

  def getVarMinMaxCompareValues(v: SMGObjectVar): (Double, Double) = {
    val minCompareValue = Try{
      val m = v.min.getOrElse("0.0") ;
      if (m == "U")
        Double.NegativeInfinity
      else
        m.toDouble
    }.getOrElse(Double.NegativeInfinity)
    val maxCompareValue = v.max.flatMap(x => Try(x.toDouble).toOption).
      getOrElse(Double.PositiveInfinity)
    (minCompareValue, maxCompareValue)
  }
}
