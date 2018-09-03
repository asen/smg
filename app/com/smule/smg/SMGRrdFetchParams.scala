package com.smule.smg

/**
  * Created by asen on 12/9/15.
  */
case class SMGRrdFetchParams(resolution: Option[Int], period: Option[String], pl: Option[String], filterNan: Boolean) {

  private def paramsMap = {
    val filterNanOpt = if (filterNan) Some("true") else None
    Map("r" -> resolution, "s" -> period, "e" -> pl, "fnan" -> filterNanOpt)
  }

  def fetchPostMap: Map[String, Seq[String]] = {
    paramsMap.map(t => (t._1, if (t._2.isDefined) Seq(t._2.get.toString) else Seq()))
  }

  def fetchUrlParams: String = {
    (for (t <- paramsMap.toList ;
          if t._2.nonEmpty) yield t._1 + "=" + t._2.get.toString).mkString("&")
  }

}
