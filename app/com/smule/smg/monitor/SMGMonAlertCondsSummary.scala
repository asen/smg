package com.smule.smg.monitor

import com.smule.smg.monitor.SMGMonAlertCondsSummary.{IndexAlertCondSummary, ObjectAlertCondSummary}

object SMGMonAlertCondsSummary {
  case class IndexAlertCondSummary(
                                  isHidden: Boolean,
                                  indexId: String,
                                  fltDesc: String,
                                  threshDesc: String,
                                  numOids: Int,
                                  numVars: Int,
                                  sampleOids: Seq[String]
                                  )

  case class ObjectAlertCondSummary(
                                     threshDesc: String,
                                     numOids: Int,
                                     numVars: Int,
                                     sampleOids: Seq[String]
                                   )
}

case class SMGMonAlertCondsSummary(
                                    remoteId: Option[String],
                                    indexConfs: Seq[IndexAlertCondSummary],
                                    objectConfs: Seq[ObjectAlertCondSummary],
                                    errMsg: Option[String] = None
                                  )
