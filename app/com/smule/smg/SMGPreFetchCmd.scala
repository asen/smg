package com.smule.smg

/**
  * Created by asen on 11/29/15.
  */

/**
  * A common for multiple rrdObjects command to be executed before the respective object fetches
  * @param id - unique identifier of the command (specified as pre_fetch in object conf)
  * @param cmd - system command to execute
  */
case class SMGPreFetchCmd(id: String, cmd: SMGCmd)

