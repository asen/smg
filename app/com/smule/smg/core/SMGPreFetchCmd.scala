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
                          preFetch: Option[String],
                          override val ignoreTs: Boolean,
                          override val childConc: Int,
                          override val notifyConf: Option[SMGMonNotifyConf],
                          override val passData: Boolean
                         ) extends SMGFetchCommand {
  val isRrdObj = false
}
