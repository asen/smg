package com.smule.smg

/**
 * Created by asen on 10/22/15.
 */

/**
  * An object representing a single rrd database
  * @param id - object id. by convention should have a class.object.value format
  * @param command - a SMG command to fetch the values for this object vars.
  * @param vars - a list of Maps each representing a variable description
  * @param title - object human readable title
  * @param rrdType - rrdtool graph type - GAUGE, COUNTER, etc
  * @param interval - update interval for this object
  * @param stack - whether to graph the lines of this object stacked
  * @param preFetch - a pre-fetch command id, to be invoked before this (and other rrdObjects sharing the same id)
  *                 object's fetch command
  */
case class SMGRrdObject(id: String,
                        command: SMGCmd,
                        vars: List[Map[String, String]],
                        title: String,
                        rrdType: String,
                        interval: Int,
                        override val dataDelay: Int,
                        stack: Boolean,
                        preFetch: Option[String],
                        rrdFile: Option[String],
                        rraDef: Option[SMGRraDef],
                        override val rrdInitSource: Option[String],
                        notifyConf: Option[SMGMonNotifyConf]
                       ) extends SMGObjectView with SMGObjectUpdate with SMGFetchCommand {


  override def showUrl:String = "/show/" + id

  override def fetchUrl(period: String, step:Option[Int]): String = "/fetch/" + id + "?s=" + period +
    "&r=" + step.map(_.toString).getOrElse("")

  private val log = SMGLogger

  private val nanList: List[Double] = vars.map(v => Double.NaN)

  private var myPrevCacheTs: Int = 0
  private var myPrevCachedValues: List[Double] = nanList

  private var myCacheTs: Int = SMGRrd.tssNow
  private var myCachedValues = nanList

  def fetchValues: List[Double] = {
    val out = this.command.run
    val ret = for (ln <- out.take(this.vars.size)) yield {
      ln.toDouble
    }
    if (ret.lengthCompare(this.vars.size) < 0) {
      val errMsg = "Bad output from external command - less lines than expected (" + ret.size + "<" + this.vars.size + ")"
      log.error(errMsg)
      log.error(out)
      throw SMGCmdException(this.command.str, this.command.timeoutSec, -1, out.mkString("\n"), errMsg)
    }
    ret
  }

  override val isAgg = false

  override val graphVarsIndexes: List[Int] = vars.indices.toList

  override val cdefVars: List[Map[String, String]] = List()

  override val refObj: Option[SMGObjectUpdate] = Some(this)

  override val isRrdObj = true

  override val pluginId: Option[String] = None
}
