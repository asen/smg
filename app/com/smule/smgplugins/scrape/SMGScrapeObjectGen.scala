package com.smule.smgplugins.scrape

import com.smule.smg.config.{SMGConfIndex, SMGConfigParser}
import com.smule.smg.core._
import com.smule.smg.grapher.SMGraphObject
import com.smule.smg.openmetrics.OpenMetricsStat

import scala.collection.mutable.ListBuffer

class SMGScrapeObjectGen(
                          scrapeTargetConf: SMGScrapeTargetConf,
                          scrapedMetrics: Seq[OpenMetricsStat],
                          log: SMGLoggerApi
                        ) {
  private def metaType2RrdType(mt: Option[String]): String = {
     mt.getOrElse("gauge") match {
       case "gauge" => "GAUGE"
       case "counter" => "DDERIVE" // XXX DDERIVE also covers float number counters
       case "summary" => "GAUGE" // TODO ?
       case "histogram" => "GAUGE" // TODO ?
       case "untyped" => "GAUGE" // TODO ?
       case x => {
         log.warn(s"SMGScrapeObjectGen.metaType2RrdType (${scrapeTargetConf.uid}): Unexpected metaType: ${x}")
         "GAUGE"
       }
     }
  }

  def processRegexReplaces(ln: String, regexReplaces: Seq[RegexReplaceConf]): String = {
    var ret = ln
    regexReplaces.foreach { rr =>
      if ((rr.filterRegex.isEmpty) || (rr.filterRegexRx.get.findFirstMatchIn(ret).isDefined)){
        ret = ret.replaceAll(rr.regex, rr.replace)
      }
    }
    ret
  }

  private def processMetaGroup(
                                grp: Seq[OpenMetricsStat],
                                idPrefix: String,
                                parentPfIds: Seq[String],
                                parentIndexId: String
                              ): (Seq[SMGRrdObject], Seq[SMGConfIndex]) = {
    val metaStat = grp.head
    val retObjects = ListBuffer[SMGRrdObject]()
    val retIxes = ListBuffer[SMGConfIndex]()
    // TODO handle histogram/summary specially
    val rrdType = metaType2RrdType(metaStat.metaType)

    val metaKey = metaStat.metaKey.map(k => processRegexReplaces(k, scrapeTargetConf.regexReplaces))

    grp.foreach { stat =>
      val ouid = idPrefix + processRegexReplaces(stat.smgUid, scrapeTargetConf.regexReplaces)
      if (!SMGConfigParser.validateOid(ouid)){
        // TODO can do better than this
        log.error(s"SMGScrapeObjectGen: ${scrapeTargetConf.uid}: invalid ouid (ignoring stat): $ouid")
        log.error(stat)
      } else {
        val varLabel = stat.name.split('_').last
        val varMu = if (rrdType == "GAUGE") "" else s"$varLabel/sec"
        val retObj = SMGRrdObject(
          id = ouid,
          parentIds = parentPfIds,
          command = SMGCmd(s":scrape get ${stat.smgUid}", scrapeTargetConf.timeoutSec),
          vars = List(Map(
            "label" -> varLabel,
            "mu" -> varMu
          )),
          title = stat.title,
          rrdType = rrdType,
          interval = scrapeTargetConf.interval,
          dataDelay = 0,
          stack = false,
          preFetch = parentPfIds.headOption,
          rrdFile = None, //TODO ?
          rraDef = None,
          notifyConf = scrapeTargetConf.notifyConf,
          rrdInitSource = None,
          labels = stat.labels.toMap
        )
        if (scrapeTargetConf.filter.isEmpty || scrapeTargetConf.filter.get.matches(retObj))
          retObjects += retObj
      } // valid oid
    }

    if (retObjects.nonEmpty && (metaKey.getOrElse("") != "")){
      retIxes += SMGConfIndex(
        id = idPrefix + metaKey.get,
        title = metaKey.get,
        flt = SMGFilter.fromPrefixLocal(idPrefix + metaKey.get),
        cols = None,
        rows = None,
        aggOp = None,
        xRemoteAgg = false,
        aggGroupBy = None,
        period = None, // TODO?
        desc = metaStat.metaHelp,
        parentId = Some(parentIndexId),
        childIds = Seq(),
        disableHeatmap = false
      )
    }
    (retObjects, retIxes)
  }

  def generateSMGObjects(): SMGScrapedObjects = {
    // process scraped metrics and produce SMG objects
    val preFetches = ListBuffer[SMGPreFetchCmd]()
    var preFetchIds = List[String]()
    if (scrapeTargetConf.parentPfId.isDefined)
      preFetchIds ::= scrapeTargetConf.parentPfId.get
    val rrdObjects = ListBuffer[SMGRrdObject]()
    val viewObjects = ListBuffer[SMGraphObject]()
    val aggObjects = ListBuffer[SMGRrdAggObject]()
    val indexes = ListBuffer[SMGConfIndex]()

    val idPrefix = scrapeTargetConf.idPrefix.getOrElse("") + scrapeTargetConf.uid + "."

    // one preFetch to get the metrics (TODO can be stripped once scrape plugon supports native "fetch")
    val scrapeFetchPfId = idPrefix + "scrape.fetch"
    val scrapeFetchPf = SMGPreFetchCmd(
      id = scrapeFetchPfId,
      command = SMGCmd(scrapeTargetConf.command, scrapeTargetConf.timeoutSec),
      preFetch = scrapeTargetConf.parentPfId,
      ignoreTs = false,
      childConc = 1,
      notifyConf = scrapeTargetConf.notifyConf,
      passData = true
    )
    preFetches += scrapeFetchPf
    preFetchIds = scrapeFetchPf.id :: preFetchIds

    // another prefetch to parse them
    val labelUidOpt = if (scrapeTargetConf.labelsInUids)
      " " + SMGScrapeCommands.PARSE_OPTION_LABEL_UIDS
    else
      ""
    val scrapeParsePfId = idPrefix + "scrape.parse"
    val scrapeParsePf = SMGPreFetchCmd(
      id = scrapeParsePfId,
      command = SMGCmd(s":scrape parse$labelUidOpt", scrapeTargetConf.timeoutSec),
      preFetch = Some(scrapeFetchPfId),
      ignoreTs = false,
      childConc = 1,
      notifyConf = scrapeTargetConf.notifyConf,
      passData = true
    )
    preFetches += scrapeParsePf
    preFetchIds = scrapeParsePf.id :: preFetchIds

    // define a top-level index for the stats
    val scrapeIndexId = idPrefix + "scrape.all"
    val scrapeTopLevelIndex = SMGConfIndex(
      id = scrapeIndexId,
      title = scrapeTargetConf.humanName + " - all graphs",
      flt = SMGFilter.fromPrefixLocal(idPrefix),
      cols = None,
      rows = None,
      aggOp = None,
      xRemoteAgg = false,
      aggGroupBy = None,
      period = None, // TODO?
      desc = None, //TODO
      parentId = scrapeTargetConf.parentIndexId,
      childIds = Seq(), // TODO?
      disableHeatmap = false
    )
    indexes += scrapeTopLevelIndex

    def myProcessMetaGroup(grp: Seq[OpenMetricsStat]): Unit = {
      val ret = processMetaGroup(grp, idPrefix, preFetchIds, scrapeIndexId)
      rrdObjects ++= ret._1
      indexes ++= ret._2
    }

    // group metrics by metaName but try to preserve order
    val curGroup = ListBuffer[OpenMetricsStat]()
    var curMetaKey = ""
    scrapedMetrics.foreach { stat =>
      if (curMetaKey != stat.metaKey.getOrElse("")) {
        if (curGroup.nonEmpty){
          myProcessMetaGroup(curGroup)
        }
        curGroup.clear()
      }
      curGroup += stat
      curMetaKey = stat.metaKey.getOrElse("")
    }
    if (curGroup.nonEmpty){
      myProcessMetaGroup(curGroup)
    }

    SMGScrapedObjects(
      preFetches = preFetches.toList,
      rrdObjects = rrdObjects.toList,
      viewObjects = viewObjects.toList,
      aggObjects = aggObjects.toList,
      indexes = indexes.toList
    )
  }
}
