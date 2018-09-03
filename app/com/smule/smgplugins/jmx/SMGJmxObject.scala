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

  override val rrdFile: Option[String] = Some(rrdDir + "/" + id + ".rrd")

  override val rraDef: Option[SMGRraDef] = None // TODO
  override val graphVarsIndexes: Seq[Int] = vars.indices
  override val cdefVars: List[Map[String, String]] = List()  // TODO
  override val stack: Boolean = false    // TODO

  override val refObj: Option[SMGObjectUpdate] = Some(this)

  override val preFetch: Option[String] = Some(pfId)

  def attrs: List[String] = vars.map(v => v.getOrElse("attr", "UNDEFINED_ATTR"))

}
