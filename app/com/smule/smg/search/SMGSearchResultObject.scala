package com.smule.smg.search

import com.smule.smg.core.{SMGIndex, SMGObjectView}
import com.smule.smg.remote.SMGRemote

case class SMGSearchResultObject(ov: SMGObjectView) extends SMGSearchResult {
  val typeStr = "Object"
  val showUrl: String = ov.dashUrl
  val title: String = ov.title
  val desc: String = s"${ov.id}: " + ov.filteredVars(true).map(_.label.getOrElse("unlabelled")).mkString(", ")
  val children: Seq[SMGSearchResult] = Seq[SMGSearchResult]()
  val remoteId: String = SMGRemote.remoteId(ov.id)
  override val idxOpt: Option[SMGIndex] = None
}
