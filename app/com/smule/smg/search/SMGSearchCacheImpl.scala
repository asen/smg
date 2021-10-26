package com.smule.smg.search

import com.smule.smg.config.{ProtectedReloadObj, SMGConfigService}
import com.smule.smg.core._
import com.smule.smg.grapher.SMGAggObjectView
import com.smule.smg.remote.{SMGRemote, SMGRemotesApi}

import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

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
                                 allPreFetches: Seq[SMGFetchCommand],
                                 pxesByRemote: Map[String, Array[Seq[String]]],
                                 sxesByRemote: Map[String, Array[Seq[String]]],
                                 tknsByRemote: Map[String, Array[Seq[String]]],
                                 wordsDict: Seq[String],
                                 labelKeysDict: Seq[String]
                               )

  private var cache: SMGSearchCacheData = SMGSearchCacheData(
    allIndexes = Seq(),
    allViewObjects = Seq(),
    allPreFetches = Seq(),
    pxesByRemote = Map(),
    sxesByRemote = Map(),
    tknsByRemote = Map(),
    wordsDict = Seq(),
    labelKeysDict = Seq()
  )

  private var cmdTknsByRemote: Map[String, Array[Seq[String]]] = Map()

  configSvc.registerReloadListener(this);
  realReload(isInit = true); // launch a reload on initialization, config svc wil call us later on reloads

  private def extendArrayBuf(ab: ArrayBuffer[mutable.Set[String]], sz: Int): Unit = {
    while (ab.size < sz) {
      val newSet = mutable.Set[String]()
      ab.append(newSet)
    }
  }

  private def sortNumbersAfterLetters(s1: String, s2: String): Boolean = {
    val ns1 = SORT_IGNORE_CHARS_RX.replaceAllIn(s1, "")
    val ns1isnum = STARTS_WITH_DIGIT_RX.findFirstMatchIn(ns1).isDefined
    val ns2 = SORT_IGNORE_CHARS_RX.replaceAllIn(s2, "")
    val ns2isnum = STARTS_WITH_DIGIT_RX.findFirstMatchIn(ns2).isDefined
    if (ns1isnum == ns2isnum) {
      if (ns1 == ns2)
        s1.toLowerCase < s2.toLowerCase
      else
        ns1.toLowerCase < ns2.toLowerCase
    } else if (ns1isnum)
      false
    else
      true
  }

  private def mySortIterable(in: Iterable[String]): Seq[String] = {
    in.toSeq.sortWith(sortNumbersAfterLetters)
  }

  private def tokensAtLevel(arr: Array[String], level: Int): Set[String] = {
    (0 to arr.length - level).map { six =>
      arr.slice(six, six + level).mkString(".")
    }.toSet
  }


  private def getIdsFromRunTrees(m: Map[Int,Seq[SMGTree[SMGFetchCommand]]]): Seq[String] = {

    def treeToIds(rt: SMGTree[SMGFetchCommand]) : Seq[String] = {
      Seq[String](rt.node.id) ++ rt.children.flatMap(c => treeToIds(c))
    }

    m.flatMap { kv =>
      kv._2.flatMap { rt =>
        treeToIds(rt)
      }
    }.toSeq.distinct
  }

  private def getRunTreeIdsByRemote: Future[Map[String, Seq[String]]] = {
    implicit val ec: ExecutionContext = configSvc.executionContexts.defaultCtx
    val localFut = Future {
      (SMGRemote.local.id, configSvc.config.getFetchCommandsTreesByInterval)
    }
    val remoteFuts = configSvc.config.remotes.map { rmt =>
      remotesApi.monitorRunTree(rmt.id, None).map(m => (rmt.id, m))
    }
    Future.sequence(Seq(localFut) ++ remoteFuts).map { byRemoteSeq =>
      byRemoteSeq.map( kv => (kv._1, getIdsFromRunTrees(kv._2))).toMap
    }
  }

  private def tokenizeId(oid: String,
                         tgts: Seq[(
                           ArrayBuffer[mutable.Set[String]],
                             (Array[String], Int, String) => Unit
                           )]) = {
    val lid = SMGRemote.localId(oid)
    val arr = lid.split("\\.")
    val arrLen = arr.length
    val maxLevels = Math.min(arrLen, configSvc.config.searchCacheMaxLevels)
    tgts.foreach { tgt =>
      extendArrayBuf(tgt._1, maxLevels)
    }
    (0 until maxLevels).foreach { ix =>
      val addDot = if (ix == arrLen - 1) "" else "."
      tgts.foreach { tgt =>
        tgt._2(arr, ix, addDot)
      }
    }
    maxLevels
  }

  private def reloadCmdTokensAsync(): Future[Boolean] = {
    implicit val ec = configSvc.executionContexts.defaultCtx
    getRunTreeIdsByRemote.map { byRemote =>
      try {
        log.info(s"SMGSearchCache.reloadCmdTokensAsync - received remotes data (${byRemote.keys.size})")
        var maxMaxLevels = 0
        val tknsByRemote = mutable.Map[String, Array[Seq[String]]]()
        byRemote.foreach { case (rmtId, cmdIds) =>
          val newTknsByLevel = ArrayBuffer[mutable.Set[String]]()
          cmdIds.foreach { cmdId =>
            val ml = tokenizeId(cmdId, Seq(
              (newTknsByLevel, { (arr: Array[String], ix: Int, addDot: String) =>
                newTknsByLevel(ix) ++= tokensAtLevel(arr, ix + 1)
              }
              )
            ))
            maxMaxLevels = Math.max(maxMaxLevels, ml)
          }
          tknsByRemote(rmtId) = newTknsByLevel.map(mySortIterable).toArray
        }
        cmdTknsByRemote = tknsByRemote.toMap
        log.info(s"SMGSearchCache.reloadCmdTokensAsync - END: " +
          s"max levels: $maxMaxLevels/${configSvc.config.searchCacheMaxLevels}")
        true
      } catch { case t: Throwable =>
        log.ex(t, s"Unexpected exception in SMGSearchCache.reloadCmdTokensAsync: ${t.getMessage}")
        false
      }
    }.recover { case t =>
      log.ex(t, s"Unexpected future failure in SMGSearchCache.reloadCmdTokensAsync: ${t.getMessage}")
      false
    }
  }

  private def realReload(isInit: Boolean): Unit = {
    log.info("SMGSearchCache.reload - BEGIN")
    val cmdTokensFut = reloadCmdTokensAsync()
    var maxMaxLevels = 0
    val newIndexes = getAllIndexes
    val newPrefetchCommands = getAllCommandsByRemote
    val byRemote = getAllViewObjectsByRemote
    val pxesByRemote = mutable.Map[String, Array[Seq[String]]]()
    val sxesByRemote = mutable.Map[String, Array[Seq[String]]]()
    val tknsByRemote = mutable.Map[String, Array[Seq[String]]]()
    val wordsDict = mutable.Set[String]()
    val labelKeysDict = mutable.Set[String]()
    byRemote.foreach { case (rmtId, newViewObjects) =>
      val newPxesByLevel = ArrayBuffer[mutable.Set[String]]()
      val newSxesByLevel = ArrayBuffer[mutable.Set[String]]()
      val newTknsByLevel = ArrayBuffer[mutable.Set[String]]()
      newViewObjects.foreach { ov =>
        val ml = tokenizeId(ov.id, Seq(
          (newPxesByLevel,
            { (arr: Array[String], ix: Int, addDot: String) =>
              newPxesByLevel(ix).add(arr.take(ix + 1).mkString(".") + addDot) }
          ),
          (newSxesByLevel,
            { (arr: Array[String], ix: Int, addDot: String) =>
              newSxesByLevel(ix).add(addDot + arr.takeRight(ix + 1).mkString(".")) }
          ),
          (newTknsByLevel,
            { (arr: Array[String], ix: Int, addDot: String) =>
              newTknsByLevel(ix) ++= tokensAtLevel(arr, ix + 1) }
          )
        ))
        ov.searchText.split("[\\s\\.]+").foreach { wrd =>
          wordsDict += wrd
        }
        ov.labels.foreach { case (k,_) =>
          labelKeysDict += k
        }
        maxMaxLevels = Math.max(maxMaxLevels, ml)
      }
      pxesByRemote(rmtId) = newPxesByLevel.map(mySortIterable).toArray
      sxesByRemote(rmtId) = newSxesByLevel.map(mySortIterable).toArray
      tknsByRemote(rmtId) = newTknsByLevel.map(mySortIterable).toArray
    }

    cache = SMGSearchCacheData(
      allIndexes = newIndexes,
      allViewObjects = byRemote.flatMap(_._2),
      allPreFetches = newPrefetchCommands.flatMap(_._2),
      pxesByRemote = pxesByRemote.toMap,
      sxesByRemote = sxesByRemote.toMap,
      tknsByRemote = tknsByRemote.toMap,
      wordsDict = mySortIterable(wordsDict),
      labelKeysDict = mySortIterable(labelKeysDict)
    )

    if (isInit) {
      log.info("SMGSearchCache.reload - init: launched command tokens processing in the background")
    } else {
      log.info("SMGSearchCache.reload - awaiting for command tokens processing to complete ...")
      Await.result(cmdTokensFut, Duration.Inf)
    }
    log.info(s"SMGSearchCache.reload - END: indexes: ${cache.allIndexes.size}, " +
      s"objects: ${cache.allViewObjects.size}, words: ${wordsDict.size}, " +
      s"max levels: $maxMaxLevels/${configSvc.config.searchCacheMaxLevels}")
  }

  private val protectedReloadObj = new ProtectedReloadObj("SMGSearchCache")

  override def reload(): Unit = {
    protectedReloadObj.reloadOrQueue(() => realReload(isInit = false))
  }

  private def allRemotes : Seq[SMGRemote] = SMGRemote.local :: configSvc.config.remotes.toList

  override def getAllIndexes: Seq[SMGIndex] = configSvc.config.indexes ++
    configSvc.config.remotes.flatMap { rmt => // preserving order
      remotesApi.byId(rmt.id).map(_.indexes).getOrElse(Seq())
    }

  private def getAllCommandsByRemote: Seq[(String,Seq[SMGFetchCommand])] = {
    Seq((SMGRemote.local.id, configSvc.config.allPreFetches)) ++
    configSvc.config.remotes.map { rmt => // preserving order
      (rmt.id, remotesApi.byId(rmt.id).map(_.allPreFetches).getOrElse(Seq()))
    }
  }

  private def getAllViewObjectsByRemote: Seq[(String,Seq[SMGObjectView])] = Seq((SMGRemote.local.id, configSvc.config.viewObjects)) ++
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
        for (pf <- cache.allPreFetches; if cnt < maxResults; if sq.preFetchMatches(pf)) {
          ret += SMGSearchResultCommand(pf)
          cnt += 1
        }
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
    if (rmtId == SMGRemote.wildcard.id) { // combine the results from all remotes
      val combinedSeq = allRemotes.flatMap { rmt =>
        getAllTokensForRemote(rmt.id, levels, byRemote, fltFn)
      }.distinct
      mySortIterable(combinedSeq).take(MAX_TOKENS_RESULTS)
    } else {
      val arr = byRemote.getOrElse(rmtId, Array())
      if (arr.isEmpty)
        return Seq[String]()
      val myLevels = if (arr.length < levels) arr.length else levels
      arr(myLevels - 1).filter(fltFn).take(MAX_TOKENS_RESULTS)
    }
  }

  private def getTokensCommon(rawFlt: String, rmtId: String, byRemote: Map[String,Array[Seq[String]]]) = {
    val flt = rawFlt.trim()
    val lvls = fltLevels(flt)
    getAllTokensForRemote(rmtId, lvls, byRemote, { s =>
      s.toLowerCase.contains(flt.toLowerCase)
    })
  }


//  private def getWordTokensForRemote(rmtId: String,
//                                     byRemote: Map[String, Seq[String]],
//                                     fltFn: (String) => Boolean): Seq[String] = {
//    val arr = byRemote.getOrElse(rmtId, Seq())
//    arr.filter(fltFn).take(MAX_TOKENS_RESULTS)
//  }

  override def getTrxTokens(rawFlt: String, rmtId: String): Seq[String] = {
    val trxFlt = rawFlt.trim()
    val words = trxFlt.split("\\s+")
    val flt = words.last
    val px = words.dropRight(1).mkString(" ")
    val tokenResults = getTokensCommon(flt, rmtId, cache.tknsByRemote)
    val wordResults = cache.wordsDict.filter { _.toLowerCase.contains(flt.toLowerCase) }.take(MAX_TOKENS_RESULTS)
    mySortIterable((tokenResults ++ wordResults).toSet).take(MAX_TOKENS_RESULTS).map { tkn => px + " " + tkn}
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

  override def getPfRxTokens(flt: String, rmtId: String): Seq[String] = {
    getTokensCommon(flt, rmtId, cmdTknsByRemote)
  }

  override def getLblsTokens(flt: String, rmtId: String): Seq[String] = {
    cache.wordsDict.filter { _.toLowerCase.contains(flt.toLowerCase) }.take(MAX_TOKENS_RESULTS)
  }

  override def getMatchingIndexes(ovsToMatch: Seq[SMGObjectView]): Seq[SMGIndex] = {
    val ovs = ovsToMatch.flatMap { ov => if (ov.isAgg) ov.asInstanceOf[SMGAggObjectView].objs else Seq(ov) }
    val allIxes: Seq[SMGIndex] = getAllIndexes
    val ret = mutable.Set[SMGIndex]()
    ovs.foreach { ov =>
      allIxes.foreach { ix =>
        val matches = (!ix.flt.matchesAnyObjectIdAndText) &&
          ((ix.flt.remotes.contains(SMGRemote.wildcard.id) ||
            (SMGRemote.remoteId(ix.id) == SMGRemote.remoteId(ov.id))) &&
            ix.flt.matches(ov))
        if (matches)
          ret.add(ix)
      }
    }
    ret.toSeq.sortBy(_.title)
  }

}


