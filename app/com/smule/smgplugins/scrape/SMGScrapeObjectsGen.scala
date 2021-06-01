package com.smule.smgplugins.scrape

import com.smule.smg.config.{SMGConfIndex, SMGConfigParser, SMGConfigService}
import com.smule.smg.core.{SMGCmd, SMGFilter, SMGLoggerApi, SMGPreFetchCmd, SMGRrdAggObject, SMGRrdObject}
import com.smule.smg.grapher.{SMGAggObjectView, SMGraphObject}
import com.smule.smg.openmetrics.{OpenMetricsGroup, OpenMetricsParser, OpenMetricsRow}
import com.smule.smg.rrd.SMGRraDef

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class SMGScrapeObjectsGen(
                           smgConfigService: SMGConfigService,
                           scrapeTargetConf: SMGScrapeTargetConf,
                           scrapedMetrics: Seq[OpenMetricsGroup],
                           log: SMGLoggerApi
                         ) {

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

  private def getRraDef(defName: Option[String]): Option[SMGRraDef] = {
    if (defName.isEmpty)
      None
    else {
      val d = smgConfigService.config.rraDefs.get(defName.get)
      if (d.isEmpty)
        log.warn(s"SMGScrapeObjectGen: Config specifies invalid rra_def value: ${defName.get}")
      d
    }
  }

  private def myPreFetchCommand(
                                 pfId: String,
                                 cmd: String,
                                 desc: Option[String],
                                 preFetch: Option[String]
                               ) = SMGPreFetchCmd(
    id = pfId,
    command = SMGCmd(cmd, scrapeTargetConf.timeoutSec),
    desc = desc,
    preFetch = preFetch,
    ignoreTs = false,
    childConc = 1,
    notifyConf = scrapeTargetConf.notifyConf,
    passData = true ,
    delay = 0.0
  )
  private def myRrdObject(ouid: String,
                          parentPfIds: Seq[String],
                          cmd: String,
                          vars: List[Map[String,String]],
                          title: String,
                          rrdType: String,
                          labels: Map[String,String]
                         ): SMGRrdObject = {
    val myRraDef = getRraDef(scrapeTargetConf.rraDef)
    SMGRrdObject(
      id = ouid,
      parentIds = parentPfIds,
      command = SMGCmd(cmd, scrapeTargetConf.timeoutSec),
      vars = vars,
      title = title,
      rrdType = rrdType,
      interval = scrapeTargetConf.interval,
      dataDelay = 0,
      delay = 0.0,
      stack = false,
      preFetch = parentPfIds.headOption,
      rrdFile = None, //TODO ?
      rraDef = myRraDef,
      notifyConf = scrapeTargetConf.notifyConf,
      rrdInitSource = None,
      labels = labels ++ scrapeTargetConf.extraLabels
    )
  }

  private def myIndex(idxId: String,
                      title: String,
                      flt: SMGFilter,
                      desc: Option[String],
                      parentIndexId: Option[String],
                      childIds: Seq[String] = Seq(),
                      cols: Option[Int] = None,
                      rows: Option[Int] = None
                     ) : SMGConfIndex =  SMGConfIndex(
    id = idxId,
    title = title,
    flt = flt,
    cols = cols,
    rows = rows,
    aggOp = None,
    xRemoteAgg = false,
    aggGroupBy = None,
    gbParam = None,
    period = None, // TODO?
    desc = desc,
    parentId = parentIndexId,
    childIds = childIds,
    disableHeatmap = false
  )

  private val sumCountGroupBucketLabels = Seq("le")

  private def processSumCountGroup(
                                    grp: OpenMetricsGroup,
                                    idPrefix: String,
                                    parentPfIds: Seq[String],
                                    parentIndexId: String,
                                    buckets: Seq[OpenMetricsRow],
                                    sumRow: Option[OpenMetricsRow],
                                    countRow: Option[OpenMetricsRow],
                                    rrdType: String,
                                    ix: Int,
                                    hasMany: Boolean
                                   ): SMGScrapedObjectsBuf = {
    val groupType = grp.metaType.getOrElse(s"INVALID_SUMCOUNT_GROUPTYPE")
    val ret = SMGScrapedObjectsBuf()
    val baseUid = if (grp.metaKey.nonEmpty) {
      grp.metaKey.get
    } else if (buckets.nonEmpty) {
      buckets.head.name.stripSuffix("_bucket")
    } else if (sumRow.nonEmpty){
      sumRow.get.name.stripSuffix("_sum")
    } else if (countRow.nonEmpty) {
      countRow.get.name.stripSuffix("_count")
    } else s"INVALID_SUMCOUNT_GROUP-$groupType" // should never happen
    val idSuffix = if (scrapeTargetConf.labelsInUids) {
      val refRow = countRow.getOrElse(sumRow.getOrElse(buckets.head))
      val labelUid = OpenMetricsParser.labelUid(baseUid, refRow.labels)
      if (labelUid.lengthCompare(scrapeTargetConf.maxUidLen) <= 0)
        labelUid
      else
        OpenMetricsParser.groupIndexUid(baseUid, Some(ix))
    } else if (hasMany)
      OpenMetricsParser.groupIndexUid(baseUid, Some(ix))
    else {
      OpenMetricsParser.safeUid(baseUid)
    }
    val titleGroupIndexId = if (hasMany) Some(ix) else None
    val smgBaseUid = idPrefix + processRegexReplaces(idSuffix, scrapeTargetConf.regexReplaces)
    var indexCols = 0
    // all buckets in one graph
    if (buckets.nonEmpty) {
      val ouid = smgBaseUid + s".${groupType}_buckets"
      val scrapeGetParams = buckets.map(_.labelUid).mkString(" ")
      val bucketVars = buckets.zipWithIndex.map { case (row, i) =>
        var lblOpt = row.labels.find(t => sumCountGroupBucketLabels.contains(t._1))
        if (lblOpt.isEmpty) lblOpt = row.labels.lastOption
        Map(
          "label" -> lblOpt.
            map(t => OpenMetricsParser.safeUid(s"${t._1}-${t._2}")).getOrElse(s"bucket_$i")
        )
      }.toList
      val myLabels = OpenMetricsParser.mergeLabels(buckets.flatMap(_.labels)).map { case (k,vs) =>
        (k, vs.mkString(","))
      }
      val retObj = myRrdObject(
        ouid = ouid,
        parentPfIds = parentPfIds,
        cmd = s":scrape get ${scrapeGetParams}",
        vars = bucketVars,
        title = grp.title(scrapeTargetConf.humanName, baseUid, titleGroupIndexId) + s" ($groupType buckets)",
        rrdType = rrdType, // TODO may need to infer that or have both a GAUGE and DDERIVE?
        labels = myLabels
      )
      if (scrapeTargetConf.filter.isEmpty || scrapeTargetConf.filter.get.matches(retObj)) {
        ret.rrdObjects += retObj
        indexCols += 1
      }
    }

    // one more graph for sum
    if (sumRow.nonEmpty) {
      val ouid = smgBaseUid + s".${groupType}_sum"
      val scrapeGetParams = sumRow.get.labelUid
      val myVars = List(Map("label" -> "sum"))
      val myLabels = sumRow.get.labelsAsMap

      val retObj = myRrdObject(
        ouid = ouid,
        parentPfIds = parentPfIds,
        cmd = s":scrape get ${scrapeGetParams}",
        vars = myVars,
        title = grp.title(scrapeTargetConf.humanName, baseUid, titleGroupIndexId) + s" ($groupType sum)",
        rrdType = rrdType, // TODO may need to infer that or have both a GAUGE and DDERIVE?
        labels = myLabels
      )
      if (scrapeTargetConf.filter.isEmpty || scrapeTargetConf.filter.get.matches(retObj)) {
        ret.rrdObjects += retObj
        indexCols += 1
      }
    }

    // one more graph for count
    if (countRow.nonEmpty) {
      val ouid = smgBaseUid + s".${groupType}_count"
      val scrapeGetParams = countRow.get.labelUid
      val myVars = List(Map("label" -> "count"))
      val myLabels = countRow.get.labelsAsMap
      val retObj = myRrdObject(
        ouid = ouid,
        parentPfIds = parentPfIds,
        cmd = s":scrape get ${scrapeGetParams}",
        vars = myVars,
        title = grp.title(scrapeTargetConf.humanName, baseUid, titleGroupIndexId) + s" ($groupType count)",
        rrdType = rrdType, // TODO may need to infer that or have both a GAUGE and DDERIVE?
        labels = myLabels
      )
      if (scrapeTargetConf.filter.isEmpty || scrapeTargetConf.filter.get.matches(retObj)) {
        ret.rrdObjects += retObj
        indexCols += 1
      }
    }

    // an extra average graph if both sum/count available
    if (sumRow.nonEmpty && countRow.nonEmpty) {
      // first a pre-fetch to get just the sum/count
      val parsePf = parentPfIds.headOption
      val sumCountPfId = smgBaseUid + ".get_sumcount"
      ret.preFetches += myPreFetchCommand(
        pfId = sumCountPfId,
        cmd = s":scrape get ${sumRow.get.labelUid}, ${countRow.get.labelUid}",
        desc = None,
        preFetch = parsePf
      )

      //an average object - dividing sum/count using rpn
      val ouid = smgBaseUid + s".${groupType}_avg"
      val myVars = List(Map("label" -> "avg"))
      val myLabels = sumRow.get.labelsAsMap ++ countRow.get.labelsAsMap
      ret.rrdObjects += myRrdObject(
        ouid = ouid,
        parentPfIds = sumCountPfId :: parentPfIds.toList,
        cmd = ":cc rpn $ds0,$ds1,/",
        vars = myVars,
        title = grp.title(scrapeTargetConf.humanName, baseUid, titleGroupIndexId) + s" ($groupType average)",
        rrdType = rrdType,
        labels = myLabels
      )
      indexCols += 1
    }

    // add an index
    if (ret.rrdObjects.nonEmpty) {
      val retIx = myIndex(
        idxId = smgBaseUid,
        title = grp.title(scrapeTargetConf.humanName, baseUid, titleGroupIndexId),
        flt = SMGFilter.fromPrefixLocal(smgBaseUid + "."),
        desc = grp.metaHelp,
        parentIndexId = Some(parentIndexId),
        cols = Some(indexCols),
        rows = Some(15)
      )
      ret.indexes += retIx
    }
    ret
  }

  private def isSumCountName(name: String): Boolean = name.endsWith("_sum") || name.endsWith("_count")

  private def processSumCountGroups(
                                      grp: OpenMetricsGroup,
                                      idPrefix: String,
                                      parentPfIds: Seq[String],
                                      parentIndexId: String
                                    ): SMGScrapedObjectsBuf = {
    val myRrdType = grp.metaType match {
      case Some("histogram") => "DDERIVE"
      case Some("summary") => "GAUGE"
      case x => {
        log.warn(s"SMGScrapeObjectGen.processSumCountGroups: Invalid groupType: ${x}, assuming GAUGE")
        "GAUGE"
      }
    }

    var myRows = grp.rows
    // TODO strip repeating label k=vs?
    //    var commonLabels = Set[(String,String)]()
    //    if (grp.rows.nonEmpty) {
    //      commonLabels ++= grp.rows.head.labels.toSet
    //      grp.rows.tail.foreach { r =>
    //        commonLabels = commonLabels.intersect(r.labels.toSet)
    //      }
    //    }

    // (Seq(buckets), sumRow, countRow)
    val sumCountGroups = ListBuffer[(Seq[OpenMetricsRow], Option[OpenMetricsRow], Option[OpenMetricsRow])]()
    var hasMore = myRows.nonEmpty
    while (hasMore){
      val curGroup = ListBuffer[OpenMetricsRow]()
      curGroup ++= myRows.takeWhile(r => !isSumCountName(r.name))
      myRows = myRows.drop(curGroup.size)
      var nextName: String = myRows.headOption.map(_.name).getOrElse("")
      if (curGroup.isEmpty && !isSumCountName(nextName)){
        log.warn(s"SMGScrapeObjectGen.processSumCountGroups: ${grp.metaKey} empty group with no sum/count")
        hasMore = false
      } else {
        // also get _sum and _count, do not assume specific order or existence of both
        val sumCount = ListBuffer[OpenMetricsRow]()
        if (isSumCountName(nextName)) {
          sumCount += myRows.head
          myRows = myRows.tail
        }
        nextName = myRows.headOption.map(_.name).getOrElse("")
        if (isSumCountName(nextName)) {
          sumCount += myRows.head
          myRows = myRows.tail
        }
        val sumOpt = sumCount.find(_.name.endsWith("_sum"))
        val countOpt = sumCount.find(_.name.endsWith("_count"))
        if (curGroup.nonEmpty || sumOpt.nonEmpty || countOpt.nonEmpty)
          sumCountGroups += ((curGroup.toList, sumOpt, countOpt))
        hasMore = myRows.nonEmpty
      }
    }

    val ret = SMGScrapedObjectsBuf()
    val hasManyGroups = sumCountGroups.lengthCompare(1) > 0
    var myParentIndexId = parentIndexId
    if (hasManyGroups) { // extra index if many groups
      val smgBaseUid = idPrefix + grp.metaKey.getOrElse("INVALID")
      ret.indexes += myIndex(
        idxId = smgBaseUid,
        title = grp.title(scrapeTargetConf.humanName, smgBaseUid, None) + s" - ${grp.metaType.getOrElse("INVALID")}",
        flt = SMGFilter.fromPrefixLocal(smgBaseUid + "."),
        desc = grp.metaHelp,
        parentIndexId = Some(parentIndexId),
        cols = Some(4),  // TODO can be 3?
        rows = Some(15)
      )
      myParentIndexId = smgBaseUid
    }
    sumCountGroups.zipWithIndex.foreach { case (t3, ix) =>
      val retGrp = processSumCountGroup(grp, idPrefix, parentPfIds, myParentIndexId,
        t3._1, t3._2, t3._3, myRrdType, ix, hasManyGroups)
      ret.mergeOther(retGrp)
    }
    ret
  }

  private def processRegularGroup(
                                     grp: OpenMetricsGroup,
                                     idPrefix: String,
                                     parentPfIds: Seq[String],
                                     parentIndexId: String
                                   ): SMGScrapedObjectsBuf = {
    val rrdType =  grp.metaType.getOrElse("gauge") match {
      case "gauge" => "GAUGE"
      case "counter" => "DDERIVE" // XXX DDERIVE also covers float number counters
      case "summary" =>
        throw new RuntimeException("SMGScrapeObjectsGen: BUG: metaType=summary passed to processRegularGroup")
      case "histogram" =>
        throw new RuntimeException("SMGScrapeObjectsGen: BUG: metaType=histogram passed to processRegularGroup")
      case "untyped" => "GAUGE" // TODO ?
      case x =>
        log.warn(s"SMGScrapeObjectsGen.processRegularGroup (${scrapeTargetConf.uid}): Unexpected metaType: ${x}")
        "GAUGE"
    }
    val ret = SMGScrapedObjectsBuf()
    if (grp.rows.nonEmpty) {
      val numRows = grp.rows.size
      val hasMany = numRows > 1
      val baseUid = grp.metaKey.getOrElse(grp.rows.head.name)
      val canHaveLabelUids = grp.rows.forall(_.labelUid.lengthCompare(scrapeTargetConf.maxUidLen) <= 0)
      grp.rows.zipWithIndex.foreach { case (row, ix) =>
        val idxSuffix = if (scrapeTargetConf.labelsInUids) {
          if (canHaveLabelUids)
            row.labelUid
          else
            OpenMetricsParser.groupIndexUid(baseUid, Some(ix)) 
        } else if (hasMany)
          OpenMetricsParser.groupIndexUid(baseUid, Some(ix))
        else {
          OpenMetricsParser.safeUid(baseUid)
        }
        val titleGroupIndexId = if (hasMany) Some(ix) else None
        val ouid = idPrefix + processRegexReplaces(idxSuffix, scrapeTargetConf.regexReplaces)
        val scrapeGetParams = row.labelUid
        val varLabel = varLabelFromStatName(row.name)
        val varMu = if (rrdType == "GAUGE") "" else s"$varLabel/sec"
        val myVars = List(Map("label" -> varLabel, "mu" -> varMu))
        val myLabels = row.labelsAsMap

        val retObj = myRrdObject(
          ouid = ouid,
          parentPfIds = parentPfIds,
          cmd = s":scrape get ${scrapeGetParams}",
          vars = myVars,
          title = grp.title(scrapeTargetConf.humanName, baseUid, titleGroupIndexId),
          rrdType = rrdType, // TODO may need to infer that or have both a GAUGE and DDERIVE?
          labels = myLabels
        )
        if (scrapeTargetConf.filter.isEmpty || scrapeTargetConf.filter.get.matches(retObj))
          ret.rrdObjects += retObj
      }

      // add an index
      if (ret.rrdObjects.nonEmpty) {
        val smgBaseUid = idPrefix + baseUid
        val filterDotSuffix = if (hasMany) "." else ""
        ret.indexes += myIndex(
          idxId = smgBaseUid,
          title = grp.title(scrapeTargetConf.humanName, baseUid, None),
          flt = SMGFilter.fromPrefixLocal(smgBaseUid + filterDotSuffix),
          desc = grp.metaHelp,
          parentIndexId = Some(parentIndexId)
        )
      }
    }
    ret
  }

  private def processMetaGroup(
                                grp: OpenMetricsGroup,
                                idPrefix: String,
                                parentPfIds: Seq[String],
                                parentIndexId: String
                              ): SMGScrapedObjectsBuf = {
    // handle histogram/summary specially
    val ret = if (grp.metaType.contains("histogram") || grp.metaType.contains("summary")) {
      processSumCountGroups(grp, idPrefix, parentPfIds, parentIndexId)
    } else {
      processRegularGroup(grp, idPrefix, parentPfIds, parentIndexId)
    }
    ret
  }


  def generateSMGObjects(): SMGScrapedObjectsBuf = {
    // process scraped metrics and produce SMG objects
    val ret = SMGScrapedObjectsBuf()
    var preFetchIds = List[String]()
    if (scrapeTargetConf.parentPfId.isDefined)
      preFetchIds ::= scrapeTargetConf.parentPfId.get
    val idPrefix = scrapeTargetConf.idPrefix.getOrElse("") + scrapeTargetConf.uid + "."
    // one preFetch to get the metrics
    val scrapeFetchPfId = idPrefix + "scrape.fetch"
    val scrapeFetchPf = myPreFetchCommand(
      pfId = scrapeFetchPfId,
      cmd = scrapeTargetConf.command,
      desc = Some(s"Scrape metrics for $idPrefix"),
      preFetch = scrapeTargetConf.parentPfId
    )
    ret.preFetches += scrapeFetchPf
    preFetchIds = scrapeFetchPf.id :: preFetchIds

    if (scrapeTargetConf.needParse) {
      val scrapeParsePfId = idPrefix + "scrape.parse"
      val scrapeParsePf = myPreFetchCommand(
        pfId = scrapeParsePfId,
        cmd = ":scrape parse",
        desc = Some(s"Parse metrics for $idPrefix"),
        preFetch = Some(scrapeFetchPfId)
      )
      ret.preFetches += scrapeParsePf
      preFetchIds = scrapeParsePf.id :: preFetchIds
    }

    // define a top-level index for the stats
    val scrapeIndexId = idPrefix + "scrape.all"
    val scrapeTopLevelIndex = myIndex(
      idxId = scrapeIndexId,
      title = scrapeTargetConf.humanName + " - all",
      flt = SMGFilter.fromPrefixLocal(idPrefix),
      desc = Some(s"Retrieved via: ${scrapeTargetConf.command}"),
      parentIndexId = scrapeTargetConf.parentIndexId
    )
    ret.indexes += scrapeTopLevelIndex

    scrapedMetrics.foreach { grp =>
      if (!grp.isEmpty){
        val grpRet = processMetaGroup(grp, idPrefix, preFetchIds, scrapeIndexId)
        ret.mergeOther(grpRet)
      }
    }
    if (scrapeTargetConf.sortIndexes)
      ret.sortIndexes()
    ret
  }
}
