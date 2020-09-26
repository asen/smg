package com.smule.smg.core

case class SMGCmdException(cmdStr: String, timeoutSec: Int, exitCode: Int, stdout: String, stderr: String) extends
  SMGFetchException(s"Command failed (exit code $exitCode): $cmdStr${
    if (stdout.isBlank) "" else s" STDOUT: $stdout"
  }${
    if (stderr.isBlank) "" else s" STDERR: $stderr"
  }")
