package com.smule.smg.monitor

import com.smule.smg.core.{SMGFetchCommand, SMGTree, SMGTreeNode}

case class SMGMonStateDetail(state: SMGMonState,
                             fetchCommand: Option[SMGFetchCommand],
                             parent: Option[SMGMonStateDetail] ) extends SMGTreeNode {

  lazy val asStack: Seq[SMGMonStateDetail] = {
    var ret = List[SMGMonStateDetail]()
    var cur : Option[SMGMonStateDetail] = Some(this)
    while (cur.isDefined) {
      ret = cur.get :: ret
      cur = cur.get.parent
    }
    ret
  }
  override val id: String = state.id

  override def parentId: Option[String] = parent.map(_.id)
}


object SMGMonStateDetail {
  def merge(in: Seq[SMGMonStateDetail]): Seq[SMGTree[SMGMonStateDetail]] = {
    mergeSeqs(in.map(_.asStack))
  }


  def mergeSeqs(in: Seq[Seq[SMGMonStateDetail]]): Seq[SMGTree[SMGMonStateDetail]] = {
    if (in.isEmpty)
      return Seq()
    val topLevel = in.groupBy(_.headOption.map(_.id))
    topLevel.toSeq.sortBy(_._1).flatMap { case (headid, seq) =>
      if (headid.isEmpty)
        None
      else {
        val node = seq.head.head
        val childSecs = seq.map(_.tail)
        Some(SMGTree(node = node, mergeSeqs(childSecs)))
      }
    }
  }
}