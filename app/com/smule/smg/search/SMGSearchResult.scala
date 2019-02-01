package com.smule.smg.search

import com.smule.smg.SMGIndex

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
