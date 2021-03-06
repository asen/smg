package com.smule.smg.core

import com.smule.smg.grapher.GraphOptions
import com.smule.smg.remote.SMGRemote

import scala.util.Try

/**
  * A SMG "object" interface, used to display a SMG object. An object is generally represented as a graph in the UI
  */
trait SMGObjectView extends SMGObjectBase {

  // override val id: String
  // override val title: String
  // override val vars: List[Map[String, String]]
  // override def interval: Int

  /**
    * whether the object lines are stacked
    */
  val stack: Boolean

  /**
   * Which vars to include
   */
  val graphVarsIndexes: Seq[Int]

  /**
    * List of Maps for each CDEF variable of this object
    */
  val cdefVars: List[Map[String, String]]

  def filteredVars(inclCdefVars: Boolean) : List[Map[String, String]] = {
    val ovars = for ( (v,i) <- vars.zipWithIndex ;
          // assume empty vars means all vars
          if graphVarsIndexes.isEmpty || graphVarsIndexes.contains(i)) yield v
    if (inclCdefVars) {
      ovars ++ cdefVars
    } else {
      ovars
    }
  }

  override def searchVars: List[Map[String, String]] = filteredVars(true)

  lazy val graphMinY: Option[Double] = {
    val myMins = filteredVars(inclCdefVars = true).map(v => v.getOrElse("min", "0.0"))
    if (myMins.exists { m => (m == "U") || m.startsWith("-") }) {
      None
    } else {
      Some(myMins.map(m => Try(m.toDouble).toOption.getOrElse(0.0)).min)
    }
  }

  lazy val graphMaxY: Option[Double] = {
    val myMaxys = for (v <- filteredVars(inclCdefVars = true) ;
                       if v.contains("maxy") ;
                       d = Try(v("maxy").toDouble).toOption ;
                       if d.isDefined)
      yield d.get
    if (myMaxys.isEmpty) {
      None
    } else {
      Some(myMaxys.max)
    }
  }

  private val SHORT_TITLE_MAX_LEN = 70

  /**
    * Get a shortened version of an object title (to be displayed inside graph images)
    *
    * @return - shortened version of the title if above SHORT_TITLE_MAX_LEN or the title if shorter
    */
  def shortTitle: String = if (title.length <= SHORT_TITLE_MAX_LEN) title else title.substring(0, SHORT_TITLE_MAX_LEN - 3) + "..."


  protected def addParamsToStr(addParams: Map[String, String], prefix: String): String = if (addParams.isEmpty) ""
    else prefix + addParams.toSeq.map(t => s"${t._1}=${java.net.URLEncoder.encode(t._2, "UTF-8")}").mkString("&")
  /**
    * The "show" url for this object
    *
    * @return - a string representing an url to display this object details
    */
  def showUrl(addParams: Map[String, String] = Map()): String = "/show/" + id + addParamsToStr(addParams, "?")

  def dashUrl: String = {
    val arr = SMGRemote.localId(id).split("\\.")
    val rmtId = SMGRemote.remoteId(id)
    val optDot = if (arr.length > 1) "." else ""
    "/dash?px=" + arr.dropRight(1).mkString(".") + optDot + "&sx=" + optDot + arr.lastOption.getOrElse("") +
      (if (rmtId != SMGRemote.local.id) "&remote=" + java.net.URLEncoder.encode(rmtId, "UTF-8") else "")
  }

  def parentDashUrl: Option[String] = {
    val rmtId = SMGRemote.remoteId(id)
    Some("/dash?px=" + SMGRemote.localId(id).split("\\.").dropRight(1).mkString(".") +
      (if (rmtId != SMGRemote.local.id) "&remote=" + java.net.URLEncoder.encode(rmtId, "UTF-8") else ""))
  }

  def fetchUrl(period: String, gopts: GraphOptions): String = {
    val sb = new StringBuilder("/fetch/" + id + "?s=" + period)
    if (gopts.step.isDefined) sb.append("&r=" + gopts.step.get)
    if (gopts.pl.isDefined) sb.append("&e=" + gopts.pl.get)
    sb.toString()
  }

  val rrdFile: Option[String]

  // only agg graph objects have that true
  val isAgg: Boolean = false

  val refObj: Option[SMGObjectUpdate]

  def inspectUrl: Option[String] = if (isAgg) None else Some(s"/inspect/$id")

  def ouId: String = refObj.map(_.id).getOrElse(id)

  def remoteId: String = SMGRemote.remoteId(id)

}
