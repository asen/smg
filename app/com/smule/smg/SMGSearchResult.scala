package com.smule.smg

/**
  * Created by asen on 9/3/16.
  */

trait SMGSearchResult {
  def remoteId: String
  def showUrl: String
  def title: String
  def desc: String
  def children: Seq[SMGSearchResult]
}

case class SMGSearchResultObject(ov: SMGObjectView) extends SMGSearchResult {
  val showUrl = ov.dashUrl
  val title = ov.title
  val desc = s"${ov.id}: " + ov.filteredVars(true).map(_.getOrElse("label", "unlabelled")).mkString(", ")
  val children = Seq()
  val remoteId = SMGRemote.remoteId(ov.id)
}

case class SMGSearchResultIndex(idx: SMGIndex, ovs: Seq[SMGObjectView]) extends SMGSearchResult {
  val showUrl = "/dash?" + idx.asUrl
  val title = idx.title
  val desc = idx.id + ": " + idx.desc.getOrElse("")
  val remoteId = SMGRemote.remoteId(idx.id)
  val children = ovs.map(SMGSearchResultObject)
}

class SMGSearchQuery(q: String) {
  private val terms = q.split("\\s+").filter(_ != "").map(_.toLowerCase)

  val isEmpty = terms.isEmpty

  private def indexText(idx: SMGIndex) = Seq(idx.id, idx.title, idx.desc.getOrElse("")).mkString(" ").toLowerCase

  private def textMatches(txt: String): Boolean = if (terms.isEmpty) false else {
    terms.forall { term =>
      txt.contains(term)
    }
  }

  def indexMatches(idx: SMGIndex) = textMatches(indexText(idx))

  def objectMatches(ov: SMGObjectView) = textMatches(ov.searchText)


}


