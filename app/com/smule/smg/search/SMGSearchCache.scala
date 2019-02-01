package com.smule.smg.search

import com.smule.smg._
import com.smule.smg.config.SMGConfigReloadListener
import com.smule.smg.core.{SMGIndex, SMGObjectView}


/**
  * Created by asen on 3/28/17.
  */
trait SMGSearchCache extends SMGConfigReloadListener {

  def getAllIndexes: Seq[SMGIndex]

  def search(q: String, maxResults: Int): Seq[SMGSearchResult]

  def getRxTokens(flt: String, rmtId: String): Seq[String]

  def getTrxTokens(flt: String, rmtId: String): Seq[String]

  def getSxTokens(flt: String, rmtId: String): Seq[String]

  def getPxTokens(flt: String, rmtId: String): Seq[String]

  def getPfRxTokens(flt: String, rmtId: String): Seq[String]

  def getMatchingIndexes(ovs: Seq[SMGObjectView]): Seq[SMGIndex]
}
