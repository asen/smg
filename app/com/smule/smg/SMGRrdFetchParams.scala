package com.smule.smg

/**
  * Created by asen on 12/9/15.
  */
case class SMGRrdFetchParams(resolution: Option[Int], period: Option[String], pl: Option[String], filterNan: Boolean) {

  private lazy val paramsMap: Map[String, Option[String]] = {
    val filterNanOpt = if (filterNan) Some("true") else None
    var ret = Map[String, Option[String]]("s" -> period)
    if (resolution.isDefined) ret ++= Map("r" -> resolution.map(_.toString))
    if (pl.getOrElse("") != "") ret ++= Map("e" -> pl)
    if (filterNanOpt.isDefined) ret ++= Map("fnan" -> filterNanOpt)
    ret
  }

  def fetchPostMap: Map[String, Seq[String]] = {
    paramsMap.map(t => (t._1, if (t._2.isDefined) Seq(t._2.get.toString) else Seq()))
  }

  def fetchUrlParams: String = {
    (for (t <- paramsMap.toList ;
          if t._2.nonEmpty) yield t._1 + "=" + t._2.get.toString).mkString("&")
  }

}
