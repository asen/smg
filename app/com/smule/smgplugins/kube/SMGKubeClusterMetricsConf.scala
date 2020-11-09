package com.smule.smgplugins.kube

import com.smule.smg.core.SMGFilter
import com.smule.smg.notify.SMGMonNotifyConf
import com.smule.smgplugins.scrape.RegexReplaceConf

case class SMGKubeClusterMetricsConf(
                                      uid: String,
                                      humanName: Option[String],
                                      interval: Option[Int],
                                      portAndPath: String,
                                      proto: Option[String],
                                      filter: Option[SMGFilter],
                                      regexReplaces: Seq[RegexReplaceConf],
                                      labelsInUids: Boolean,
//                                    parentPfId: Option[String],
//                                    parentIndexId: Option[String],
                                      notifyConf: Option[SMGMonNotifyConf]
                                    ) {
  lazy val hname: String = humanName.getOrElse(uid)
}
