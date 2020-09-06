package com.smule.smgplugins.scrape

import com.smule.smg.core.SMGFilter
import com.smule.smg.monitor.SMGMonNotifyConf

case class SMGScrapeTargetConf(
                                uid: String,
                                humanName: String,
                                command: String,
                                timeoutSec: Int,
                                confOutput: String,
                                confOutputBackupExt: Option[String],
                                filter: SMGFilter, // XXX somewhat misusing that as not all properties are relevant
                                interval: Int,
                                parentPfId: Option[String],
                                parentIndexId: Option[String],
                                idPrefix: Option[String],
                                notifyConf: Option[SMGMonNotifyConf]
                              ) {
   lazy val inspect: String = s"uid=$uid humanName=$humanName command=$command timeout=$timeoutSec " +
     s"confOutput=$confOutput parentPfId=$parentPfId filter: ${filter.humanText}"
}
