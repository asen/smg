package com.smule.smg.core

case class SMGCmdException(cmdStr: String, timeoutSec: Int, exitCode: Int, stdout: String, stderr: String) extends
  SMGFetchException(s"Command failed (exit code $exitCode): $cmdStr\nSTDOUT:\n${stdout}\nSTDERR:\n${stderr}")
