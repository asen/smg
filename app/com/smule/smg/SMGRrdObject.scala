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
                        stack: Boolean,
                        preFetch: Option[String],
                        rrdFile: Option[String],
                        rraDef: Option[SMGRraDef]
                       ) extends SMGObjectView with SMGObjectUpdate {


  def showUrl:String = "/show/" + id

  def fetchUrl(period: String): String = "/fetch/" + id + "?s=" + period

  private val log = SMGLogger

  override def fetchValues: List[Double] = {
    val out = SMGCmd.runCommand(this.command.str, this.command.timeoutSec)
    val ret = for (ln <- out.take(this.vars.size)) yield { ln.toDouble }
    if (ret.size < this.vars.size) {
      val errMsg = "Bad output from external command - less lines than expected (" + ret.size + "<" + this.vars.size  + ")"
      log.error(errMsg)
      log.error(out)
      throw new SMGCmdException(this.command.str, this.command.timeoutSec, -1, out.mkString("\n"), errMsg)
    }
    ret
  }

  val rrdDir = None

  val isAgg = false

  val graphVarsIndexes = (0 until vars.size).toList

  val cdefVars: List[Map[String, String]] = List()

  override val refObj: Option[SMGObjectUpdate] = Some(this)

}
