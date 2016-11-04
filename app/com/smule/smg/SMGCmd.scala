package com.smule.smg

import scala.sys.process._

/**
  * Created by asen on 11/17/15.
  */
case class SMGCmd(str: String, timeoutSec: Int = 30) {
  /**
    * Execute this command and collect standard output
    * @return - a list of strings each representing a command output line
    */
  def run = SMGCmd.run(this)
}

case class SMGCmdException(cmdStr: String, timeoutSec: Int, exitCode: Int, stdout: String, stderr: String) extends
  RuntimeException(s"Command failed (exit code $exitCode): $cmdStr")

object SMGCmd {
  val log = SMGLogger

  val DEFAULT_TIMEOUT_COMMAND = "timeout"

  val DEFAULT_EXECUTOR_COMMAND = Seq("bash", "-c")

  var timeoutCommand = DEFAULT_TIMEOUT_COMMAND
  var executorCommand = DEFAULT_EXECUTOR_COMMAND

  /**
    * To be called once during initialization, set the "timeout" command name (gtimeout on Mac with homebrew)
    * @param newCmd - timeout command executable
    */
  def setTimeoutCommand(newCmd: String) = timeoutCommand.synchronized { timeoutCommand = newCmd }

  /**
    * To be called once during initialization - command to use instead of the default Seq("bash","-c")
    * @param newCmdSeq - new executor command sequence to use
    */
  def setExecutorCommand(newCmdSeq: Seq[String]) = executorCommand.synchronized { executorCommand = newCmdSeq }

  /**
    * Execute a SMG command and collect stdout
    * @param c - SMGCmd object representing the comand
    * @return - a list of strings each representing a command output line
    */
  def run(c:SMGCmd, myEnv: Map[String,String] = Map()) = runCommand(c.str, c.timeoutSec, myEnv)

  /**
    * Execute a system command (string) using a provided timeout and collect its standard output
    * @param cmd - command string
    * @param timeoutSecs - time in seconds to wait for the command to finish before terminating
    * @return - a list of strings each representing a command output line
    */
  def runCommand(cmd: String, timeoutSecs: Int, myEnv: Map[String,String] = Map()): List[String] = {
    val (exit, out, err) = system(cmd, timeoutSecs, myEnv)
    if (exit != 0) {
      log.error("Bad exit value from command (" + exit + "): " + cmd)
      out.foreach( (x) => log.error( "STDOUT: " + x) )
      err.foreach( (x) => log.error( "STDERR: " + x) )
      throw new SMGCmdException(cmd, timeoutSecs, exit, out.mkString("\n"), err.mkString("\n"))
    }
    out
  }

  private def system(cmd: String, timeout: Int, myEnv: Map[String,String]): (Int, List[String], List[String]) = {
    val cmdSeq = Seq(timeoutCommand, timeout.toString) ++ executorCommand ++ Seq(cmd)
    log.debug("RUN_COMMAND: tms=" + timeout + " : " + cmdSeq)
    val qb = Process(cmdSeq, None, myEnv.toSeq:_*)
    var out = List[String]()
    var err = List[String]()

    val exit = qb ! ProcessLogger((s) => out ::= s, (s) => err ::= s)

    (exit, out.reverse, err.reverse)
  }
}
