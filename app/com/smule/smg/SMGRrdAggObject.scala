package com.smule.smg

/**
  * A RRD (update) object used to keep track of values produced by applying an aggregate op (SUM/AVG etc)
  * to the values fetched for multiple other update objects.
  *
  * Not to be confused with SMGAggObjectView which is a view-only object produced by applying the op on
  * multiple rrd files during display time.
  */
case class SMGRrdAggObject(id: String,
                           ous: Seq[SMGObjectUpdate],
                           aggOp: String,
                           vars: List[Map[String, String]],
                           title: String,
                           rrdType: String,
                           interval: Int,
                           override val dataDelay: Int,
                           stack: Boolean,
                           rrdFile: Option[String],
                           rraDef: Option[SMGRraDef],
                           override val rrdInitSource: Option[String],
                           notifyConf: Option[SMGMonNotifyConf]
                          ) extends SMGObjectView with SMGObjectUpdate {


  override def showUrl:String = "/show/" + id

  override def fetchUrl(period: String, step: Option[Int]): String = "/fetch/" + id + "?s=" + period +
    "&r=" + step.map(_.toString).getOrElse("")

  def fetchValues(confSvc: SMGConfigService): List[Double] = {
    val sources = ous.map(ou => confSvc.getCachedValues(ou)).toList
    SMGRrd.mergeValues(aggOp, sources)
  }

  override val preFetch: Option[String] = None

  override val isAgg = false

  override val graphVarsIndexes: List[Int] = vars.indices.toList

  override val cdefVars: List[Map[String, String]] = List()

  override val refObj: Option[SMGObjectUpdate] = Some(this)

  override val pluginId: Option[String] = None
}

