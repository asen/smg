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
                        rraDef: Option[SMGRraDef],
                        override val rrdInitSource: Option[String],
                        notifyConf: Option[SMGMonNotifyConf]
                       ) extends SMGObjectView with SMGObjectUpdate with SMGFetchCommand {


  def showUrl:String = "/show/" + id

  def fetchUrl(period: String): String = "/fetch/" + id + "?s=" + period

  private val log = SMGLogger

  private val nanList: List[Double] = vars.map(v => Double.NaN)

  private var myPrevCacheTs: Int = 0
  private var myPrevCachedValues: List[Double] = nanList

  private var myCacheTs: Int = SMGRrd.tssNow
  private var myCachedValues = nanList

  override def cachedValues: List[Double] = {
    if (isCounter) {
      // XXX this is only to deal with counter overflows which we don't want to mess our aggregated stats
      val deltaTime = myCacheTs - myPrevCacheTs
      if (deltaTime > 0 && deltaTime <= 3 * interval) {
        val rates = myCachedValues.zip(myPrevCachedValues).map { case (cur, prev) => (cur - prev) / deltaTime }
        val isGood = rates.zip(vars).forall { case (rate, v) =>
          (!rate.isNaN) && (rate >= v.getOrElse("min", "0.0").toDouble) && v.get("max").forall(_.toDouble >= rate)
        }
        if (isGood)
          myCachedValues
        else {
          nanList
        }
      } else {
        nanList // time delta outside range
      }
    } else
      myCachedValues
  }

  override def invalidateCachedValues(): Unit = {
    myPrevCachedValues = myCachedValues
    myPrevCacheTs = 0
    myCachedValues = nanList
    myCacheTs = SMGRrd.tssNow
  }

  override def inspect: String = super.inspect +
    s", myCacheTs=$myCacheTs, myCachedValues=$myCachedValues" +
    s", myPrevCacheTs=$myPrevCacheTs, myPrevCachedValues=$myPrevCachedValues"

  override def fetchValues: List[Double] = {
    try {
      val out = SMGCmd.runCommand(this.command.str, this.command.timeoutSec)
      val ret = for (ln <- out.take(this.vars.size)) yield {
        ln.toDouble
      }
      if (ret.size < this.vars.size) {
        val errMsg = "Bad output from external command - less lines than expected (" + ret.size + "<" + this.vars.size + ")"
        log.error(errMsg)
        log.error(out)
        throw SMGCmdException(this.command.str, this.command.timeoutSec, -1, out.mkString("\n"), errMsg)
      }
      myPrevCachedValues = myCachedValues
      myPrevCacheTs = myCacheTs
      myCachedValues = ret
      myCacheTs = SMGRrd.tssNow
      ret
    } catch {
      case t: Throwable => {
        invalidateCachedValues()
        throw t
      }
    }
  }

  override val isAgg = false

  override val graphVarsIndexes: List[Int] = vars.indices.toList

  override val cdefVars: List[Map[String, String]] = List()

  override val refObj: Option[SMGObjectUpdate] = Some(this)

  override val isRrdObj = true

  override val pluginId: Option[String] = None
}
