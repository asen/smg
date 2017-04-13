package com.smule.smg

/**
  * Created by asen on 4/7/17.
  */
case class SMGRrdAggObject(id: String,
                           ous: Seq[SMGObjectUpdate],
                           aggOp: String,
                           vars: List[Map[String, String]],
                           title: String,
                           rrdType: String,
                           interval: Int,
                           stack: Boolean,
                           rrdFile: Option[String],
                           rraDef: Option[SMGRraDef],
                           override val rrdInitSource: Option[String],
                           notifyConf: Option[SMGMonNotifyConf]
                          ) extends SMGObjectView with SMGObjectUpdate {


  def showUrl:String = "/show/" + id

  def fetchUrl(period: String): String = "/fetch/" + id + "?s=" + period

  private val log = SMGLogger

  private val nanList = vars.map(v => Double.NaN)
  private var myCachedValues = nanList

  override def invalidateCachedValues(): Unit = {
    myCachedValues = nanList
  }

  override def cachedValues: List[Double] = myCachedValues

  override val preFetch: Option[String] = None

  override def fetchValues: List[Double] = {
    try {
      val sources = ous.map(_.cachedValues).toList
      val ret = SMGRrd.mergeValues(aggOp, sources)
      myCachedValues = ret
      ret
    } catch {
      case t: Throwable => {
        myCachedValues = nanList
        throw t
      }
    }
  }

  override val isAgg = false

  override val graphVarsIndexes: List[Int] = vars.indices.toList

  override val cdefVars: List[Map[String, String]] = List()

  override val refObj: Option[SMGObjectUpdate] = Some(this)

  override val pluginId: Option[String] = None
}
