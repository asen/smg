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
                                regexReplaces: Seq[RegexReplaceConf],
                                labelsInUids: Boolean
                              ) {
   lazy val inspect: String = s"uid=$uid humanName=$humanName interval=$interval command=$command " +
     s"timeout=$timeoutSec confOutput=$confOutput parentPfId=$parentPfId labelsInUids=$labelsInUids " +
     s"filter: ${filter.humanText}"

  def confOutputFile(confDir: Option[String]): String = {
    if (confDir.isDefined && !Paths.get(confOutput).isAbsolute){
      confDir.get.stripSuffix(File.separator) + File.separator + confOutput
    } else confOutput
  }
}
