package com.smule.smgplugins.cc.ln

import com.smule.smg.core._
import com.smule.smgplugins.cc.rx.SMGRegexCommands
import com.smule.smgplugins.cc.shared.{CCStringUtil, SMGCCRunner}

// :cc ln [opts] index1 [index2...]
//   -s <separator_regex> | --separator <separator_regex> (default is any whitespace - \s+)
//   -js <join_str> | --join-separator <join_str> (default is space)
//   -jn | --join-new-line
// :cc ln -s ", " 1
// split a line using the separator (regex) string and output the specified 1-based elements
// separated by space. 0 means to output the entire input line
class SMGLineCommand(log: SMGLoggerApi) extends SMGCCRunner {

  // :cc ln -s ", " 1
  def runCommand(action: String, paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {
    if (parentData.isEmpty) {
      throwOnError(action, paramStr, timeoutSec, s"Line command expects parent data")
    }
    var sepStr = "\\s+"
    var joinStr = " "
    var joinNewLines = false
    var tokens = CCStringUtil.tokenize(paramStr.stripLeading())
    while (tokens.nonEmpty && tokens.head.startsWith("-")){
      val opt = tokens.head
      opt match {
        case "-s"| "--separator" =>
          tokens = tokens.tail
          sepStr = tokens.headOption.getOrElse("")
        case "-js" | "--join-separator" =>
          tokens = tokens.tail
          joinStr = tokens.headOption.getOrElse("")
        case "-jn" | "--join-new-line" =>
          joinNewLines = true
        case _ => log.warn(s"Invalid ln parse option (ignored): $opt")
      }
      if (tokens.nonEmpty)
        tokens = tokens.tail
    }
    if (tokens.isEmpty)
      throwOnError(action, paramStr, timeoutSec,
        s"SMGLineCommands: Invalid command (no indexes specified): args=${tokens.mkString(" ")}")
    val idxes = try {
      tokens.map(_.toInt)
    } catch { case t: Throwable =>
      throwOnError(action, paramStr, timeoutSec,
        s"SMGLineCommands: Invalid command (no valid indexes specified): " +
          s"args=${tokens.mkString(" ")}, err=${t.getMessage}")
    }
    val parentRes = parentData.get.res
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    val maxSplit = idxes.max + 2 // idxes are 1-based, 0 means entire line
    val strLst = inpLines.flatMap { inp =>
      val arr = inp.split(sepStr, maxSplit)
      val out = idxes.flatMap { ix =>
        if (ix == 0)
          Some(inp)
        else
          arr.lift(ix - 1)
      }
      if (joinNewLines){
        out
      } else Seq(out.mkString(joinStr))
    }
    CommandResultListString(strLst, None)
  }
}
