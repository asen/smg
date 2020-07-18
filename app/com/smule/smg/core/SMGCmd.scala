package com.smule.smg.core

import java.io.{BufferedReader, ByteArrayInputStream, InputStream, InputStreamReader, OutputStream}

import scala.collection.mutable.ListBuffer
import scala.sys.process._

/**
  * Created by asen on 11/17/15.
  */
case class SMGCmd(str: String, timeoutSec: Int = 30) {
  /**
    * Execute this command and collect standard output
    * @return - a list of strings each representing a command output line
    */
  def run(stdin: Option[String] = None): List[String] = SMGCmd.run(this, stdin)
}

object SMGCmd {
  val log: SMGLogger.type = SMGLogger

  val DEFAULT_TIMEOUT_COMMAND: String = "timeout"

  val DEFAULT_EXECUTOR_COMMAND: Seq[String] = Seq("bash", "-c")

  var timeoutCommand: String = DEFAULT_TIMEOUT_COMMAND
  var executorCommand: Seq[String] = DEFAULT_EXECUTOR_COMMAND

  /**
    * To be called once during initialization, set the "timeout" command name (gtimeout on Mac with homebrew)
    * @param newCmd - timeout command executable
    */
  def setTimeoutCommand(newCmd: String): Unit = timeoutCommand.synchronized { timeoutCommand = newCmd }

  /**
    * To be called once during initialization - command to use instead of the default Seq("bash","-c")
    * @param newCmdSeq - new executor command sequence to use
    */
  def setExecutorCommand(newCmdSeq: Seq[String]): Unit = executorCommand.synchronized { executorCommand = newCmdSeq }

  /**
    * Execute a SMG command and collect stdout
    * @param c - SMGCmd object representing the comand
    * @return - a list of strings each representing a command output line
    */
  def run(c:SMGCmd, stdin: Option[String], myEnv: Map[String,String] = Map()): List[String] =
    runCommand(c.str, c.timeoutSec, myEnv, stdin)

  /**
    * Execute a system command (string) using a provided timeout and collect its standard output
    * @param cmd - command string
    * @param timeoutSecs - time in seconds to wait for the command to finish before terminating
    * @return - a list of strings each representing a command output line
    */
  def runCommand(cmd: String, timeoutSecs: Int, myEnv: Map[String,String] = Map(), stdin: Option[String] = None): List[String] = {
    val (exit, out, err) = system(cmd, timeoutSecs, myEnv, stdin)
    if (exit != 0) {
      log.error("Bad exit value from command (" + exit + "): " + cmd)
      out.foreach( (x) => log.error( "STDOUT: " + x) )
      err.foreach( (x) => log.error( "STDERR: " + x) )
      throw SMGCmdException(cmd, timeoutSecs, exit, out.mkString("\n"), err.mkString("\n"))
    }
    out
  }

  private def system(cmd: String, timeout: Int,
                              myEnv: Map[String,String], stdin: Option[String]): (Int, List[String], List[String]) = {
    val cmdSeq = Seq(timeoutCommand, timeout.toString) ++ executorCommand ++ Seq(cmd)
    log.debug("RUN_COMMAND: tms=" + timeout + " : " + cmdSeq)

    val qb = Process(cmdSeq, None, myEnv.toSeq:_*)

    var out = ListBuffer[String]()
    var err = ListBuffer[String]()

    def readFromStream(in: InputStream, tgt: ListBuffer[String]): Unit = {
        val reader = new BufferedReader(new InputStreamReader(in))
        var line = reader.readLine
        while (line != null) {
          tgt += line
          line = reader.readLine
        }
    }

    def readProcessStdout(ins: InputStream) {
      try {
        readFromStream(ins, out)
      } catch { case t: Throwable =>
        log.error(s"SMGCmd.system: unexpected error in readProcessStdout: ${t.getMessage}", t)
      }
    }

    def readProcesStderr(errs: InputStream) {
      try {
        readFromStream(errs, err)
      } catch { case t: Throwable =>
        log.error(s"SMGCmd.system: unexpected error in readProcesStderr: ${t.getMessage}", t)
      }
    }

    def writeProcessStdin(out: OutputStream) {
      if (stdin.isDefined) {
        try {
          out.write(stdin.get.getBytes)
          out.flush()
        } catch { case t: Throwable =>
          log.error(s"SMGCmd.system: unexpected error in writeProcessStdin: ${t.getMessage}", t)
        }
      }
      out.close()
    }

    val io = new ProcessIO(writeProcessStdin, readProcessStdout, readProcesStderr)

    val exit = qb.run(io).exitValue()

    (exit, out.toList, err.toList)
  }
}
