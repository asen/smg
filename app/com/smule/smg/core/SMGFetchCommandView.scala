package com.smule.smg.core
import com.smule.smg.monitor.SMGMonNotifyConf

/**
  * A (de)serializable version of SMGFetchCommand, used to keep a local copy
  * of a remote runtree
  */
case class SMGFetchCommandView(id: String,
                               command: SMGCmd,
                               preFetch: Option[String],
                               isRrdObj: Boolean,
                               passData: Boolean
                              ) extends SMGFetchCommand {
  val notifyConf: Option[SMGMonNotifyConf] = None // TODO ???
}
