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
    * All applicable for search vars definitions (object views can define subsets)
    * @return
    */
  def searchVars : List[Map[String, String]]

  lazy val searchText = Seq(id,
    title,
    searchVars.map(v => v.getOrElse("label","") + " " + v.getOrElse("mu", "") ).mkString(" ")
  ).mkString(" ").toLowerCase

}
