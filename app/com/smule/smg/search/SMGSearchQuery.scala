package com.smule.smg.search

import com.smule.smg.core.{SMGFetchCommand, SMGIndex, SMGObjectView, SMGPreFetchCmd}
import com.smule.smg.remote.SMGRemote

import scala.util.Try

class SMGSearchQuery(q: String) {
  private val terms = q.split("\\s+").filter(_ != "").map(_.toLowerCase)

  val isEmpty: Boolean = terms.isEmpty

  private def indexText(idx: SMGIndex) = {
    val searchRemoteIdSeq = if (SMGRemote.isRemoteObj(idx.id)) Seq(SMGRemote.remoteId(idx.id)) else Seq(SMGRemote.localName)
    (Seq(SMGRemote.localId(idx.id)) ++ searchRemoteIdSeq ++ Seq(idx.title, idx.desc.getOrElse(""))).mkString(" ").toLowerCase
  }

  private def preFetchText(cmd: SMGFetchCommand) = {
    val searchRemoteIdSeq = if (SMGRemote.isRemoteObj(cmd.id)) Seq(SMGRemote.remoteId(cmd.id)) else Seq(SMGRemote.localName)
    (Seq(SMGRemote.localId(cmd.id)) ++ searchRemoteIdSeq ++ Seq(cmd.command.str, cmd.commandDesc.getOrElse(""))).mkString(" ").toLowerCase
  }

  private def textMatches(txt: String): Boolean = if (terms.isEmpty) false else {
    terms.forall { term =>
      if (term.startsWith("()")) {
        val asCiRegex = Try(("(?i)" + term.substring(2)).r).getOrElse("MATCH_NOTHING^".r)
        asCiRegex.findFirstIn(txt).nonEmpty
      } else
        txt.contains(term)
    }
  }

  def indexMatches(idx: SMGIndex): Boolean = textMatches(indexText(idx))

  def objectMatches(ov: SMGObjectView): Boolean = textMatches(ov.searchText)

  def preFetchMatches(pf: SMGFetchCommand): Boolean = textMatches(preFetchText(pf))
}
