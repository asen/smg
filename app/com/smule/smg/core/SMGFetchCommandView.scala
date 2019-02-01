package com.smule.smg.core

/**
  * A (de)serializable version of SMGFetchCommand, used to keep a local copy
  * of a remote runtree
  */
case class SMGFetchCommandView(id: String,
                               command: SMGCmd,
                               preFetch: Option[String],
                               isRrdObj: Boolean) extends SMGFetchCommand
