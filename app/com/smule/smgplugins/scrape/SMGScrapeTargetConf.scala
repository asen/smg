package com.smule.smgplugins.scrape

import java.io.File
import java.nio.file.Paths

import com.smule.smg.core.SMGFilter
import com.smule.smg.monitor.SMGMonNotifyConf

case class SMGScrapeTargetConf(
                                uid: String,
                                humanName: String,
                                command: String,
                                timeoutSec: Int,
                                private val confOutput: String,
                                confOutputBackupExt: Option[String],
                                filter: SMGFilter,
                                interval: Int,
                                parentPfId: Option[String],
                                parentIndexId: Option[String],
                                idPrefix: Option[String],
                                notifyConf: Option[SMGMonNotifyConf],
                                regexReplaces: Seq[RegexReplaceConf]
                              ) {
   lazy val inspect: String = s"uid=$uid humanName=$humanName command=$command timeout=$timeoutSec " +
     s"confOutput=$confOutput parentPfId=$parentPfId filter: ${filter.humanText}"

  def confOutputFile(confDir: Option[String]): String = {
    if (confDir.isDefined && !Paths.get(confOutput).isAbsolute){
      confDir.get.stripSuffix(File.separator) + File.separator + confOutput
    } else confOutput
  }
}
