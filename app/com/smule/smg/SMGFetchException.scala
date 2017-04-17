package com.smule.smg

/**
  * Created by asen on 4/15/17.
  */
class SMGFetchException(msg: String)  extends RuntimeException(msg)

case class SMGCmdException(cmdStr: String, timeoutSec: Int, exitCode: Int, stdout: String, stderr: String) extends
  SMGFetchException(s"Command failed (exit code $exitCode): $cmdStr")
