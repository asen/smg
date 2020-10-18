package com.smule.smg.monitor
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGFetchCommand, SMGObjectUpdate}

class SMGMonInternalCmdState(
                            var cmd: SMGFetchCommand,
                            var objs: Seq[SMGObjectUpdate],
                            var pluginId: Option[String],
                            val configSvc: SMGConfigService,
                            val monLog: SMGMonitorLogApi,
                            val notifSvc: SMGMonNotifyApi
                            ) extends SMGMonInternalState {

  // XXX the cmd can change but the id - not (would be diff object)
  override val id: String = cmd.id

  override def ouids: Seq[String] = objs.map(_.id)

  def intervals: Seq[Int] = objs.map(_.interval).sorted.distinct

  override protected def vixOpt: Option[Int] = None

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    configSvc.fetchComandNotifyCmdsAndBackoff(cmd)
  }

  override def getMaxHardErrorCount: Int = {
    configSvc.fetchComandNotifyStrikes(cmd)
  }

  override def text: String = {
    val desc = if (cmd.commandDesc.isDefined) s"desc=`${cmd.commandDesc.get}` " else ""
    s"${cmd.id}(intvl${if (intervals.lengthCompare(1) == 0)
      "" else "s"}=${intervals.mkString(",")}): ${desc}cmd=${cmd.command.str} ; $currentStateDesc"
  }

  override def oid: Option[String] = if (cmd.isUpdateObj) Some(cmd.id) else None

  override def pfId: Option[String] = Some(cmd.id)

  override def alertKey: String = cmd.id

  override def parentId: Option[String] = cmd.preFetch

  def processError(ts: Int, exitCode :Int, errors: List[String], isInherited: Boolean): Unit = {
    val errorMsg = s"Fetch error: exit=$exitCode, OUTPUT: " + errors.mkString("\n")
    addState(SMGState(ts, SMGState.FAILED, errorMsg), isInherited)
  }

  def processSuccess(ts: Int, isInherited: Boolean): Unit = {
    addState(SMGState(ts, SMGState.OK, "OK"), isInherited)
  }
}
