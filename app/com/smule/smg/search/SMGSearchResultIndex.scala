package com.smule.smg.search

import com.smule.smg._

case class SMGSearchResultIndex(idx: SMGIndex, ovs: Seq[SMGObjectView]) extends SMGSearchResult {
  val typeStr = "Index"
  val showUrl: String = "/dash?" + idx.asUrl
  val remoteId: String = SMGRemote.remoteId(idx.id)
  val title: String = idx.title
  val desc: String = idx.id + idx.desc.map(s => ": " + s).getOrElse("")
  val children: Seq[SMGSearchResultObject] = ovs.map(SMGSearchResultObject)
  override val idxOpt: Option[SMGIndex] = Some(idx)
}
