package com.smule.smgplugins.jmx

import com.smule.smg._

/**
  * Created by asen on 5/16/16.
  */


case class SMGJmxObject(baseId: String,
                        id: String,
                        pfId: String,
                        title: String,
                        hostPort: String,
                        jmxName: String,
                        rrdType: String,
                        vars: List[Map[String, String]],
                        rrdDir: String,
                        interval : Int,
                        pluginId: Option[String],
                        notifyConf: Option[SMGMonNotifyConf]
                       ) extends SMGObjectUpdate with SMGObjectView {

  private var currentValues = vars.map { v => 0.0 }

  def setCurrentValues(newVals: List[Double]): Unit = {
    currentValues.synchronized {
      currentValues = newVals
    }
  }
  override def fetchValues: List[Double] = {
    currentValues.synchronized {
      currentValues
    }
  }

  override def showUrl:String = "/show/" + id

  override def fetchUrl(period: String): String = "/fetch/" + id + "?s=" + period

  override val rrdFile: Option[String] = Some(rrdDir + "/" + id + ".rrd")

  override val isAgg: Boolean = false

  override val rraDef: Option[SMGRraDef] = None // TODO
  override val graphVarsIndexes: Seq[Int] = vars.indices
  override val cdefVars: List[Map[String, String]] = List()  // TODO
  override val stack: Boolean = false    // TODO

  override val refObj: Option[SMGObjectUpdate] = Some(this)

  override val preFetch: Option[String] = Some(pfId)

  def attrs: List[String] = vars.map(v => v.getOrElse("attr", "UNDEFINED_ATTR"))

  override def cachedValues: List[Double] = fetchValues

  override def invalidateCachedValues(): Unit = {
    currentValues = vars.map { v => 0.0 }
  }
}