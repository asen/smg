package com.smule.smg

/**
  * Class encapsulating graph display options. All params are optional.
  *
  * @param step - step/resolution to use in the graphs (default - rrdtool default for the period)
  * @param pl - period length - to limit the end period of graphs
  * @param xsort - whether to apply x-sort (by avg val) to the objects
  * @param disablePop - disable period-over-period dashed line
  * @param disable95pRule - disable 95%-ile ruler (line)
  * @param maxY - limit the Y-axis of the graph to that value (higher values not displayed)
  */
case class GraphOptions(
                         step: Option[Int],
                         pl: Option[String],
                         xsort: Option[Int],
                         disablePop: Boolean,
                         disable95pRule: Boolean,
                         maxY: Option[Double],
                         minY: Option[Double],
                         logY: Boolean
                       ) {

  def fnSuffix(period:String): String = {
    val goptsSx = (if (disablePop) "-dpp" else "") + (if (disable95pRule) "-d95p" else "") +
      maxY.map(x => s"-mxy$x").getOrElse("") + minY.map(x => s"-mny$x").getOrElse("") + (if (logY) "-logy" else "")

    "-" + SMGRrd.safePeriod(period, m2sec = false) + pl.map("-pl" + SMGRrd.safePeriod(_, m2sec = false)).getOrElse("") +
      step.map("-" + _).getOrElse("") + goptsSx
  }
}

object GraphOptions {

  val default = GraphOptions(
    step = None,
    pl = None,
    xsort = None,
    disablePop = false,
    disable95pRule = false,
    maxY = None,
    minY = None,
    logY = false
  )

  def withSome(
                step: Option[Int] = None,
                pl: Option[String] = None,
                xsort: Option[Int] = None,
                disablePop: Boolean = false,
                disable95pRule: Boolean = false,
                maxY: Option[Double] = None,
                minY: Option[Double] = None,
                logY: Boolean = false
              ): GraphOptions = {
    GraphOptions(step, pl, xsort, disablePop, disable95pRule, maxY, minY, logY)
  }

}
