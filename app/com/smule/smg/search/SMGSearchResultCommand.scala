package com.smule.smg.search
import com.smule.smg.core.{SMGFetchCommand, SMGFilter, SMGIndex}
import com.smule.smg.remote.SMGRemote

case class SMGSearchResultCommand(cmd: SMGFetchCommand) extends SMGSearchResult {
  override def typeStr: String = "Command"

  override def remoteId: String = SMGRemote.remoteId(cmd.id)

  override def showUrl: String = "/dash?" + SMGFilter.fromParentId(cmd.id).asUrl

  override def title: String = cmd.id

  override def desc: String = {
    "cmd=" + cmd.command.str + cmd.commandDesc.map(d => s" (${d})").getOrElse("")
  }

  val children: Seq[SMGSearchResult] = Seq[SMGSearchResult]()

  override val idxOpt: Option[SMGIndex] = None
}
