package com.smule.smg

import javax.inject.{Inject, Singleton}

import scala.collection.{SortedSet, mutable}
import scala.collection.mutable.{ArrayBuffer, ListBuffer}


/**
  * Created by asen on 3/28/17.
  */
trait SMGSearchCache extends SMGConfigReloadListener {

  def search(q: String, maxResults: Int): Seq[SMGSearchResult]

  def getRxTokens(suffixFlt: String, rmtId: String): Seq[String]

  def getTrxTokens(suffixFlt: String, rmtId: String): Seq[String]

  def getSxTokens(suffixFlt: String, rmtId: String): Seq[String]

  def getPxTokens(prefixFlt: String, rmtId: String): Seq[String]
}


@Singleton
class SMGSearchCacheImpl @Inject() (configSvc: SMGConfigService,
                                    remotesApi: SMGRemotesApi) extends SMGSearchCache {

  private val log = SMGLogger

  private val MAX_TOKENS_RESULTS = 100

  private val STARTS_WITH_DIGIT_RX = "^\\d+".r

  private val SORT_IGNORE_CHARS_RX = "[^a-zA-Z0-9]".r

  case class SMGSearchCacheData(
                                 allIndexes: Seq[SMGIndex],
                                 allViewObjects: Seq[SMGObjectView],
                                 pxesByRemote: Map[String, Array[Seq[String]]],
                                 sxesByRemote: Map[String, Array[Seq[String]]],
                                 tknsByRemote: Map[String, Array[Seq[String]]]

  )


  private var cache: SMGSearchCacheData = null

  configSvc.registerReloadListener(this);
  try {
    reload(); // reload on initialization, config svc wil call us later
  } catch {
    case t: Throwable => {
      log.ex(t, "Error loading search cache")
      cache = SMGSearchCacheData(
        allIndexes = Seq(),
        allViewObjects = Seq(),
        pxesByRemote = Map(),
        sxesByRemote = Map(),
        tknsByRemote = Map()
      )
    }
  }

  private def extendArrayBuf(ab: ArrayBuffer[mutable.Set[String]], sz: Int) = {
    while (ab.size < sz) {
      val newSet = mutable.Set[String]()
      ab.append(newSet)
    }
  }

  private def sortNumbersAfterLetters(s1: String, s2: String) = {
    val ns1 = SORT_IGNORE_CHARS_RX.replaceAllIn(s1, "")
    val ns1isnum = STARTS_WITH_DIGIT_RX.findFirstMatchIn(ns1).isDefined
    val ns2 = SORT_IGNORE_CHARS_RX.replaceAllIn(s2, "")
    val ns2isnum = STARTS_WITH_DIGIT_RX.findFirstMatchIn(ns2).isDefined
    if (ns1isnum == ns2isnum)
      ns1.toLowerCase < ns2.toLowerCase
    else if (ns1isnum)
      false
    else
      true
  }

  def mySortSet(in: mutable.Set[String]): Seq[String] = {
    in.toSeq.sortWith(sortNumbersAfterLetters)
  }

  private def mergeRemotesData(toMerge: mutable.Map[String, Array[Seq[String]]]): Array[Seq[String]] = {
    if (toMerge.isEmpty) {
      return Array()
    }
    val maxLength = toMerge.values.maxBy(_.length).length
    (0 until maxLength).map { ix =>
      val sets = toMerge.values.map(arr => if (ix < arr.length) arr(ix) else Set[String]())
      sets.foldLeft(mutable.Set[String]()) { (soFar, newSet) => soFar ++ newSet }
    }.map { s =>
      mySortSet(s)
    }.toArray
  }

  private def tokensAtLevel(arr: Array[String], level: Int) = {
    (0 to arr.length - level).map { six =>
      arr.slice(six, six + level).mkString(".")
    }.toSet
  }

  override def reload(): Unit = {
    log.debug("SMGSearchCache.reload")
    val newIndexes = getAllIndexes
    val byRemote = getAllViewObjectsByRemote
    val pxesByRemote = mutable.Map[String, Array[Seq[String]]]()
    val sxesByRemote = mutable.Map[String, Array[Seq[String]]]()
    val tknsByRemote = mutable.Map[String, Array[Seq[String]]]()
    byRemote.foreach { case (rmtId, newViewObjects) =>
      val newPxesByLevel = ArrayBuffer[mutable.Set[String]]()
      val newSxesByLevel = ArrayBuffer[mutable.Set[String]]()
      val newTknsByLevel = ArrayBuffer[mutable.Set[String]]()
      newViewObjects.foreach { ov =>
        val lid = SMGRemote.localId(ov.id)
        val arr = lid.split("\\.")
        val arrLen = arr.length
        extendArrayBuf(newPxesByLevel, arrLen)
        extendArrayBuf(newSxesByLevel, arrLen)
        extendArrayBuf(newTknsByLevel, arrLen)
        arr.indices.foreach { ix =>
          val addDot = if (ix == arrLen - 1) "" else "."
          newPxesByLevel(ix).add(arr.take(ix + 1).mkString(".") + addDot)
          newSxesByLevel(ix).add(addDot + arr.takeRight(ix + 1).mkString("."))
          newTknsByLevel(ix) ++= tokensAtLevel(arr, ix + 1)
        }
      }
      pxesByRemote(rmtId) = newPxesByLevel.map(mySortSet).toArray
      sxesByRemote(rmtId) = newSxesByLevel.map(mySortSet).toArray
      tknsByRemote(rmtId) = newTknsByLevel.map(mySortSet).toArray
    }
    pxesByRemote(SMGRemote.wildcard.id) = mergeRemotesData(pxesByRemote)
    sxesByRemote(SMGRemote.wildcard.id) = mergeRemotesData(sxesByRemote)
    tknsByRemote(SMGRemote.wildcard.id) = mergeRemotesData(tknsByRemote)
    cache = SMGSearchCacheData(
      allIndexes = newIndexes,
      allViewObjects = byRemote.flatMap(_._2),
      pxesByRemote = pxesByRemote.toMap,
      sxesByRemote = sxesByRemote.toMap,
      tknsByRemote = tknsByRemote.toMap
    )
  }

  private def getAllIndexes: Seq[SMGIndex] = configSvc.config.indexes ++
    configSvc.config.remotes.flatMap { rmt => // preserving order
      remotesApi.byId(rmt.id).map(_.indexes).getOrElse(Seq())
    }

  private def getAllViewObjectsByRemote: Seq[(String,Seq[SMGObjectView])] = Seq(("", configSvc.config.viewObjects)) ++
    configSvc.config.remotes.map { rmt => // preserving order
      (rmt.id, remotesApi.byId(rmt.id).map(_.viewObjects).getOrElse(Seq()))
    }

  override def search(q: String, maxResults: Int): Seq[SMGSearchResult] = {
    // search through
    // all indexes (title/desc)
    // objects (title/labels/command)
    val sq = new SMGSearchQuery(q)
    if (sq.isEmpty)
      Seq()
    else {
      val ret = ListBuffer[SMGSearchResult]()
      var cnt = 0
      for (ix <- cache.allIndexes; if cnt < maxResults; if sq.indexMatches(ix)) {
        ret += SMGSearchResultIndex(ix, Seq()) // TODO get matching objects
        cnt += 1
      }
      if (cnt < maxResults) {
        for (ov <- cache.allViewObjects; if cnt < maxResults; if sq.objectMatches(ov)) {
          ret += SMGSearchResultObject(ov)
          cnt += 1
        }
      }
      ret.toList
    }
  }

  private def fltLevels(flt: String): Int = {
    val arr = flt.split("\\.")
    if (flt.endsWith(".")) arr.length + 1 else arr.length
  }

  private def getAllTokensForRemote(rmtId: String,
                                    levels: Int,
                                    byRemote: Map[String, Array[Seq[String]]],
                                    fltFn: (String) => Boolean): Seq[String] = {
    val arr = byRemote.getOrElse(rmtId, Array())
    if (arr.isEmpty)
      return Seq[String]()
    val myLevels = if (arr.length < levels) arr.length else levels
    arr(myLevels - 1).filter(fltFn).take(MAX_TOKENS_RESULTS)
  }

  private def getTokensCommon(rawFlt: String, rmtId: String, byRemote: Map[String,Array[Seq[String]]]) = {
    val flt = rawFlt.trim()
    val lvls = fltLevels(flt)
    getAllTokensForRemote(rmtId, lvls, byRemote, { s =>
      s.toLowerCase.contains(flt.toLowerCase)
    })
  }

  override def getTrxTokens(trxFlt: String, rmtId: String): Seq[String] = {
    val words = trxFlt.split("\\s+")
    val flt = words.last
    val px = words.dropRight(1).mkString(" ")
    getTokensCommon(flt, rmtId, cache.tknsByRemote).map { tkn => px + " " + tkn}
  }

  override def getPxTokens(flt: String, rmtId: String): Seq[String] = {
    getTokensCommon(flt, rmtId, cache.pxesByRemote)
  }

  override def getSxTokens(flt: String, rmtId: String): Seq[String] = {
    getTokensCommon(flt, rmtId, cache.sxesByRemote)
  }

  override def getRxTokens(flt: String, rmtId: String): Seq[String] = {
    getTokensCommon(flt, rmtId, cache.tknsByRemote)
  }

}
