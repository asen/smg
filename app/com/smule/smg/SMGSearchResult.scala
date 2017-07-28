package com.smule.smg

import scala.util.Try

/**
  * Created by asen on 9/3/16.
  */

trait SMGSearchResult {
  def typeStr: String
  def remoteId: String
  def showUrl: String
  def title: String
  def desc: String
  def children: Seq[SMGSearchResult]
  val idxOpt: Option[SMGIndex]
}

case class SMGSearchResultObject(ov: SMGObjectView) extends SMGSearchResult {
  val typeStr = "Object"
  val showUrl: String = ov.dashUrl
  val title: String = ov.title
  val desc: String = s"${ov.id}: " + ov.filteredVars(true).map(_.getOrElse("label", "unlabelled")).mkString(", ")
  val children: Seq[SMGSearchResult] = Seq[SMGSearchResult]()
  val remoteId: String = SMGRemote.remoteId(ov.id)
  override val idxOpt: Option[SMGIndex] = None
}

case class SMGSearchResultIndex(idx: SMGIndex, ovs: Seq[SMGObjectView]) extends SMGSearchResult {
  val typeStr = "Index"
  val showUrl: String = "/dash?" + idx.asUrl
  val remoteId: String = if (SMGRemote.isRemoteObj(idx.id))SMGRemote.remoteId(idx.id) else "Local"
  val title: String = s"($remoteId) " + idx.title
  val desc: String = idx.id + idx.desc.map(s => ": " + s).getOrElse("")
  val children: Seq[SMGSearchResultObject] = ovs.map(SMGSearchResultObject)
  override val idxOpt: Option[SMGIndex] = Some(idx)
}

class SMGSearchQuery(q: String) {
  private val terms = q.split("\\s+").filter(_ != "").map(_.toLowerCase)

  val isEmpty: Boolean = terms.isEmpty

  private def indexText(idx: SMGIndex) = {
    val searchRemoteIdSeq = if (SMGRemote.isRemoteObj(idx.id)) Seq() else Seq("local")
    (searchRemoteIdSeq ++ Seq(idx.id, idx.title, idx.desc.getOrElse(""))).mkString(" ").toLowerCase
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


}


