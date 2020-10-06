package com.smule.smgplugins.scrape

import com.smule.smg.config.{SMGConfIndex, SMGConfigParser, SMGConfigService}
import com.smule.smg.core._
import com.smule.smg.grapher.{SMGAggObjectView, SMGraphObject}
import com.smule.smg.openmetrics.OpenMetricsStat
import com.smule.smg.rrd.SMGRraDef

import scala.collection.mutable.ListBuffer

class SMGScrapeObjectGen(
                          smgConfigService: SMGConfigService,
                          scrapeTargetConf: SMGScrapeTargetConf,
                          scrapedMetrics: Seq[OpenMetricsStat],
                          log: SMGLoggerApi
                        ) {
  private val aggUidStr = "agg."
  private val nonAggUidStr = "dtl."

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

  private val MAX_AUTO_VAR_LABEL_LEN = 15

  private def varLabelFromStatName(statName: String): String = {
    val varLabelUnderscore = statName.split('_').last
    val varLabelDot = statName.split('.').last
    val varLabelAuto = if (varLabelDot.nonEmpty && varLabelDot.length <= varLabelUnderscore.length)
      varLabelDot
    else if (varLabelUnderscore.nonEmpty)
      varLabelUnderscore
    else
      ""
    if (varLabelAuto.nonEmpty && varLabelAuto.lengthCompare(MAX_AUTO_VAR_LABEL_LEN) <= 0)
      varLabelAuto
    else
      "value"
  }

  private def getRraDef(defName: Option[String], rraTyp: String): Option[SMGRraDef] = {
    if (defName.isEmpty)
      None
    else {
      val d = smgConfigService.config.rraDefs.get(defName.get)
      if (d.isEmpty)
        log.warn(s"SMGScrapeObjectGen: Config specifies invalid rra_$rraTyp value: ${defName.get}")
      d
    }
  }

  private def processMetaGroup(
                                grp: Seq[OpenMetricsStat],
                                idPrefix: String,
                                parentPfIds: Seq[String],
                                parentAggIndexId: String,
                                parentNonAggIndexId: String
                              ): (Seq[SMGRrdObject], Seq[SMGRrdAggObject], Seq[SMGConfIndex]) = {
    val metaStat = grp.head
    val retObjects = ListBuffer[SMGRrdObject]()
    val retIxes = ListBuffer[SMGConfIndex]()
    val aggObjects = ListBuffer[SMGRrdAggObject]()
    // TODO handle histogram/summary specially
    val rrdType = metaType2RrdType(metaStat.metaType)
    val metaKey = metaStat.metaKey.map(k => processRegexReplaces(k, scrapeTargetConf.regexReplaces))
    val hasDetails = grp.lengthCompare(1) > 0
    val myNonAggUidStr = if (hasDetails) nonAggUidStr else aggUidStr
    val myNonAggRraDef = if (hasDetails)
      getRraDef(scrapeTargetConf.rraDefDtl, "dtl")
    else
      getRraDef(scrapeTargetConf.rraDefAgg, "agg")
    grp.foreach { stat =>
      val ouid = idPrefix + myNonAggUidStr + processRegexReplaces(stat.smgUid, scrapeTargetConf.regexReplaces)
      if (!SMGConfigParser.validateOid(ouid)){
        // TODO can do better than this?
        log.error(s"SMGScrapeObjectGen: ${scrapeTargetConf.uid}: invalid ouid (ignoring stat): $ouid")
        log.error(stat)
      } else {
        val varLabel = varLabelFromStatName(stat.name)
        val varMu = if (rrdType == "GAUGE") "" else s"$varLabel/sec"
        val retObj = SMGRrdObject(
          id = ouid,
          parentIds = parentPfIds,
          command = SMGCmd(s":scrape get ${stat.smgUid}", scrapeTargetConf.timeoutSec),
          vars = List(Map(
            "label" -> varLabel,
            "mu" -> varMu
          )),
          title = stat.title(scrapeTargetConf.humanName),
          rrdType = rrdType,
          interval = scrapeTargetConf.interval,
          dataDelay = 0,
          stack = false,
          preFetch = parentPfIds.headOption,
          rrdFile = None, //TODO ?
          rraDef = myNonAggRraDef,
          notifyConf = scrapeTargetConf.notifyConf,
          rrdInitSource = None,
          labels = stat.labels.toMap
        )
        if (scrapeTargetConf.filter.isEmpty || scrapeTargetConf.filter.get.matches(retObj))
          retObjects += retObj
      } // valid oid
    }

    val nonAggIndexId = idPrefix + nonAggUidStr + metaKey.get
    val aggOp = "SUMN" // TODO ?? may be depend on type
    val metaKeyReplaced = processRegexReplaces(metaKey.getOrElse(""), scrapeTargetConf.regexReplaces)
    val aggOuid = idPrefix + aggUidStr + metaKeyReplaced
    val titlePrefix = s"(${scrapeTargetConf.humanName}) "
    val objsSuffix = if (retObjects.lengthCompare(1) == 0) "" else "s"
    val aggTitle = titlePrefix + metaKeyReplaced +
      s" ($aggOp, ${retObjects.size} obj${objsSuffix})"

    if (retObjects.nonEmpty && retObjects.tail.nonEmpty && (metaKey.getOrElse("") != "")){
      val rraDef = getRraDef(scrapeTargetConf.rraDefAgg, "agg")
      aggObjects += SMGRrdAggObject(
        id = aggOuid,
        ous = retObjects,
        aggOp = aggOp,
        vars = retObjects.head.vars,
        title = aggTitle,
        rrdType = rrdType,
        interval = retObjects.head.interval,
        dataDelay = retObjects.head.dataDelay,
        stack = retObjects.head.stack,
        rrdFile = None,
        rraDef = rraDef,
        labels = SMGAggObjectView.mergeLabels(retObjects.map(_.labels)),
        rrdInitSource = None,
        notifyConf = scrapeTargetConf.notifyConf
      )
      retIxes += SMGConfIndex(
        id = nonAggIndexId,
        title = titlePrefix + metaKeyReplaced + " (details)",
        flt = SMGFilter.fromPrefixLocal(idPrefix + nonAggUidStr + metaKeyReplaced),
        cols = None,
        rows = None,
        aggOp = None,
        xRemoteAgg = false,
        aggGroupBy = None,
        gbParam = None,
        period = None, // TODO?
        desc = metaStat.metaHelp.map(_ + " - details"),
        parentId = Some(parentNonAggIndexId),
        childIds = Seq(),
        disableHeatmap = false
      )
    }

    val aggIndexId = idPrefix + aggUidStr + metaKey.get
    retIxes += SMGConfIndex(
        id = aggIndexId,
        title = if (hasDetails) aggTitle else titlePrefix + metaKeyReplaced,
        flt = SMGFilter.fromPrefixLocal(idPrefix + aggUidStr + metaKeyReplaced),
        cols = None,
        rows = None,
        aggOp = None,
        xRemoteAgg = false,
        aggGroupBy = None,
        gbParam = None,
        period = None, // TODO?
        desc = metaStat.metaHelp,
        parentId = Some(parentAggIndexId),
        childIds = if (hasDetails) Seq(nonAggIndexId) else Seq(),
        disableHeatmap = false
      )

    (retObjects, aggObjects, retIxes)
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

    // one preFetch to get the metrics
    val scrapeFetchPfId = idPrefix + "scrape.fetch"
    val scrapeFetchPf = SMGPreFetchCmd(
      id = scrapeFetchPfId,
      command = SMGCmd(scrapeTargetConf.command, scrapeTargetConf.timeoutSec),
      desc = Some(s"Scrape metrics for $idPrefix"),
      preFetch = scrapeTargetConf.parentPfId,
      ignoreTs = false,
      childConc = 1,
      notifyConf = scrapeTargetConf.notifyConf,
      passData = true
    )
    preFetches += scrapeFetchPf
    preFetchIds = scrapeFetchPf.id :: preFetchIds

    if (scrapeTargetConf.needParse) {
      // another prefetch to parse them
      val labelUidOpt = if (scrapeTargetConf.labelsInUids)
        " " + SMGScrapeCommands.PARSE_OPTION_LABEL_UIDS
      else
        ""
      val scrapeParsePfId = idPrefix + "scrape.parse"
      val scrapeParsePf = SMGPreFetchCmd(
        id = scrapeParsePfId,
        command = SMGCmd(s":scrape parse$labelUidOpt", scrapeTargetConf.timeoutSec),
        desc = Some(s"Parse metrics for $idPrefix"),
        preFetch = Some(scrapeFetchPfId),
        ignoreTs = false,
        childConc = 1,
        notifyConf = scrapeTargetConf.notifyConf,
        passData = true
      )
      preFetches += scrapeParsePf
      preFetchIds = scrapeParsePf.id :: preFetchIds
    }

    // define a top-level index for the stats
    val scrapeAggsIndexId = idPrefix + "scrape." + aggUidStr + "all"
    val scrapeNonAggsIndexId = idPrefix + "scrape." + nonAggUidStr + "all"
    val scrapeTopLevelAggIndex = SMGConfIndex(
      id = scrapeAggsIndexId,
      title = scrapeTargetConf.humanName + " - summaries",
      flt = SMGFilter.fromPrefixLocal(idPrefix + aggUidStr),
      cols = None,
      rows = None,
      aggOp = None,
      xRemoteAgg = false,
      aggGroupBy = None,
      gbParam = None,
      period = None, // TODO?
      desc = None, //TODO
      parentId = scrapeTargetConf.parentIndexId,
      childIds = Seq(), // TODO?
      disableHeatmap = false
    )
    indexes += scrapeTopLevelAggIndex
    val scrapeTopLevelNonAggIndex = SMGConfIndex(
      id = scrapeNonAggsIndexId,
      title = scrapeTargetConf.humanName + " - details",
      flt = SMGFilter.fromPrefixLocal(idPrefix + nonAggUidStr),
      cols = None,
      rows = None,
      aggOp = None,
      xRemoteAgg = false,
      aggGroupBy = None,
      gbParam = None,
      period = None, // TODO?
      desc = None, //TODO
      parentId = Some(scrapeAggsIndexId),
      childIds = Seq(), // TODO?
      disableHeatmap = false
    )
    indexes += scrapeTopLevelNonAggIndex

    def myProcessMetaGroup(grp: Seq[OpenMetricsStat]): Unit = {
      val ret = processMetaGroup(grp, idPrefix, preFetchIds, scrapeAggsIndexId, scrapeNonAggsIndexId)
      rrdObjects ++= ret._1
      aggObjects ++= ret._2
      indexes ++= ret._3
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
