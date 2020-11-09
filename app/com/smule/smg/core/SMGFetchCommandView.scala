package com.smule.smg.core

import com.smule.smg.notify.SMGMonNotifyConf

/**
  * A (de)serializable version of SMGFetchCommand, used to keep a local copy
  * of a remote runtree
  */
case class SMGFetchCommandView(id: String,
                               command: SMGCmd,
                               commandDesc: Option[String],
                               preFetch: Option[String],
                               override val isUpdateObj: Boolean,
                               passData: Boolean,
                               delay: Double
                              ) extends SMGFetchCommand {
  val notifyConf: Option[SMGMonNotifyConf] = None // TODO ???
}
