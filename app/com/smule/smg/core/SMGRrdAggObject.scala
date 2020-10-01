package com.smule.smg.core

import com.smule.smg.config.SMGConfigService
import com.smule.smg.monitor.SMGMonNotifyConf
import com.smule.smg.rrd.{SMGRraDef, SMGRrd}

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
                           notifyConf: Option[SMGMonNotifyConf],
                           labels: Map[String, String]
                          ) extends SMGObjectView with SMGObjectUpdate with SMGFetchCommand {

  override val preFetch: Option[String] = None
  override val parentIds: Seq[String] = Seq() // TODO - or all ous parents?

  override val graphVarsIndexes: List[Int] = vars.indices.toList

  override val cdefVars: List[Map[String, String]] = List()

  override val refObj: Option[SMGObjectUpdate] = Some(this)

  override val pluginId: Option[String] = None

  override val command: SMGCmd = SMGCmd(s"+$aggOp: ${ous.size} objs")
  override val passData: Boolean = true
}
