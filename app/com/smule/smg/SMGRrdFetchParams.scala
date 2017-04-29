package com.smule.smg

/**
  * Created by asen on 12/9/15.
  */
case class SMGRrdFetchParams(resolution: Option[Int], start: Option[String], end: Option[String], filterNan: Boolean) {

  def fetchUrlParams: String = {
    val filterNanOpt = if (filterNan) Some("true") else None
    (for (t <- Map("r" -> resolution, "s" -> start, "e" -> end, "fnan" -> filterNanOpt).toList ;
          if t._2.nonEmpty) yield t._1 + "=" + t._2.get.toString).mkString("&")
  }

}
