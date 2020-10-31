package com.smule.smg.core

import com.smule.smg.monitor.SMGMonNotifyConf

/**
  * A common for multiple rrdObjects command to be executed before the respective object fetches
 *
  * @param id - unique identifier of the command (specified as pre_fetch in object conf)
  * @param command - system command to execute
  * @param preFetch - optional "parent" pre fetch command, this command will wait for its parent
  *                 to succeed before being executed
  */
case class SMGPreFetchCmd(id: String,
                          command: SMGCmd,
                          desc: Option[String],
                          preFetch: Option[String],
                          override val ignoreTs: Boolean,
                          override val childConc: Int,
                          override val notifyConf: Option[SMGMonNotifyConf],
                          override val passData: Boolean,
                          override val delay: Double
                         ) extends SMGFetchCommand {
  override val commandDesc: Option[String] = desc

  lazy val inspect: String = s"SMGPreFetchCmd: id=$id command=$command desc=${desc.getOrElse("None")} " +
    s"pre_fetch=${preFetch.getOrElse("None")} ignoreTs=$ignoreTs childConc=$childConc passData=$passData " +
    s"delay=$delay notifyConf=${notifyConf.map(_.inspect).getOrElse("None")}"

  override val cmdSearchText: String = super.cmdSearchText
}
