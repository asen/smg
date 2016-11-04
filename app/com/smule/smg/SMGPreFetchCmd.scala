package com.smule.smg

/**
  * Created by asen on 11/29/15.
  */

/**
  * A common for multiple rrdObjects command to be executed before the respective object fetches
  * @param id - unique identifier of the command (specified as pre_fetch in object conf)
  * @param command - system command to execute
  * @param preFetch - optional "parent" pre fetch command, this command will wait for its parent
  *                 to succeed before being executed
  */
case class SMGPreFetchCmd(id: String, command: SMGCmd, preFetch: Option[String]) extends SMGFetchCommand {
  val isRrdObj = false
}

