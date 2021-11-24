package com.smule.smg.core

import com.smule.smg.config.SMGConfigService
import com.smule.smg.notify.SMGMonNotifyConf
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
                           vars: List[SMGObjectVar],
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
                          ) extends SMGObjectUpdate with SMGFetchCommand {

  override val preFetch: Option[String] = None
  override val parentIds: Seq[String] = ous.flatMap { ou => ou.parentIds }.distinct

  override val graphVarsIndexes: List[Int] = vars.indices.toList

  override val cdefVars: List[SMGObjectVar] = List()

  override val pluginId: Option[String] = None

  private def cmdUids(maxCmdUids: Int = 10): String = ous.take(maxCmdUids).map(_.id).mkString(", ") +
    (if (ous.lengthCompare(maxCmdUids) > 0) s", ...(${ous.size - maxCmdUids} more)" else "")

  override val command: SMGCmd = SMGCmd(s"+$aggOp(${ous.size}): ${cmdUids()}")
  override val passData: Boolean = true
  override val ignorePassedData: Boolean = false

  val commandDesc: Option[String] = Some(title)
  override val delay: Double = 0.0

  override val searchText: String = (Seq(super.searchText, cmdSearchText) ++
    ous.map(_.searchText)).mkString(" ")
}
