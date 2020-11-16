package com.smule.smgplugins.cc.rx

import com.smule.smg.core._

import scala.util.Try
import scala.util.matching.Regex

// TODO - doc
// :cc rxe <regex_with_match_groups> <mgrpoupIdx1> <mgrpoupIdx2>
//    apply the regex on the entire input (as a string, possibly with new lines) and print
//    each match group on a separate line
// :cc rxel <regex_with_match_groups> <mgrpoupIdx1> <mgrpoupIdx2>
//    apply the regex on each input line separately and print each line's match groups on one line
//    separated by space
//  e.g. :cc rxel |.*(\\d+).*| 1

// :cc rxm <regex>
//    returns entire input (all lines) if matching, error otherwise
// :cc rxml <regex>
//    returns individual matching lines, error if no matching lines (like grep)

// :cc rx_repl <regex> [<replacement>]
//   for each line in input replace all occurrences of regex with replacements (empty if not specified) str

object SMGRegexCommands {
  val VALID_COMMANDS = Set("rxe", "rxel", "rxm", "rxml", "rx_repl")
  val VALID_DELIMITERS = Set('\'', '"', '|', '\\', '/')

  def delimitedStr(inp: String): (String, String) = {
    if (inp.isBlank)
      return ("", "")
    var realInp = inp.stripLeading()
    val delim = if (VALID_DELIMITERS.contains(realInp(0))){
      val ret = realInp(0)
      realInp = realInp.drop(1)
      ret
    } else ' '
    val delimPos = realInp.indexOf(delim)
    if (delimPos < 0)
      (realInp, "")
    else {
      val ret = realInp.splitAt(delimPos)
      (ret._1, ret._2.drop(1))
    }
  }

  def getIntegerSeq(rem: String, minVal: Int = 0): Seq[Int] = {
    if (rem.isEmpty)
      Seq()
    else
      rem.split("\\s+").flatMap(x => Try(x.toInt).toOption).filter(_ >= minVal)
  }

}

class SMGRegexCommands(log: SMGLoggerApi) {

  private def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":cc $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  private def validateParams(action: String, paramStr: String, timeoutSec: Int,
                             parentData: Option[ParentCommandData]): (Regex, String) = {
    if (parentData.isEmpty) {
      throwOnError(action, paramStr, timeoutSec, s"Regex commands expect parent data")
    }
    val (rxStr, rem) = SMGRegexCommands.delimitedStr(paramStr)
    val regex = try {
      rxStr.r
    } catch { case t: Throwable =>
      throwOnError(action, paramStr, timeoutSec, s"Invalid regex: ${t.getMessage}")
    }
    (regex, rem.stripLeading())
  }

  private def getMatchGroups(rem: String): Seq[Int] = SMGRegexCommands.getIntegerSeq(rem)

  private def getAllMatches(regex: Regex, inp: String, mgroups: Seq[Int]): Seq[String] = {
    val am = regex.findAllMatchIn(inp)
    am.map { m =>
      if (mgroups.isEmpty)
        m.matched
      else {
        mgroups.map { gi =>
          m.group(gi)
        }.mkString(" ")
      }
    }.toSeq
  }

  private def regexMatches(regex: Regex, inp: String): Boolean = {
    regex.findFirstIn(inp).isDefined
  }

  private def rxeCommand(paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem) = validateParams("rxe", paramStr, timeoutSec, parentData)
    val mgroups = getMatchGroups(rem)
    val inp = parentData.get.res.asStr
    val strLst = getAllMatches(regex, inp, mgroups)
    if (strLst.isEmpty)
      throwOnError("rxe", paramStr, timeoutSec, s"Regex did not match (${regex}): $inp")
    CommandResultListString(strLst.toList, None)
  }

  private def rxelCommand(paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem) = validateParams("rxel", paramStr, timeoutSec, parentData)
    val mgroups = getMatchGroups(rem)
    val parentRes = parentData.get.res
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    val strLst = inpLines.flatMap { inp =>
      val matches = getAllMatches(regex, inp, mgroups)
      if (matches.isEmpty)
        None
      else
        Some(matches.mkString(" "))
    }
    if (strLst.isEmpty)
      throwOnError("rxel", paramStr, timeoutSec, s"Regex did not match (${regex}): $inpLines")
    CommandResultListString(strLst, None)
  }

  private def rxmCommand(paramStr: String, timeoutSec: Int,
                 parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem) = validateParams("rxm", paramStr, timeoutSec, parentData)
    val inp = parentData.get.res.asStr
    if (!regexMatches(regex, inp))
      throwOnError("rxm", paramStr, timeoutSec, s"Regex did not match (${regex}): $inp")
    parentData.get.res
  }

  private def rxmlCommand(paramStr: String, timeoutSec: Int,
                 parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem) = validateParams("rxml", paramStr, timeoutSec, parentData)
    val parentRes = parentData.get.res
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    val strLst = inpLines.filter(ln => regexMatches(regex, ln))
    if (strLst.isEmpty)
      throwOnError("rxml", paramStr, timeoutSec, s"Regex did not match (${regex}): $inpLines")
    CommandResultListString(strLst, None)
  }

  // regex replace, sed-like
  private def rxReplCommand(paramStr: String, timeoutSec: Int,
                            parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem) = validateParams("rx_repl", paramStr, timeoutSec, parentData)
    val parentRes = parentData.get.res
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    val strLst = inpLines.map { ln =>
      regex.replaceAllIn(ln, rem)
    }
    CommandResultListString(strLst, None)
  }

  def rxCommand(action: String, paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {
    if (!SMGRegexCommands.VALID_COMMANDS.contains(action))
      throwOnError(action, paramStr, timeoutSec,
        s"SMGRegexCommands: Invalid action - must be one of: ${SMGRegexCommands.VALID_COMMANDS.mkString(",")}")
    action match {
      case "rxe"  => rxeCommand(paramStr, timeoutSec, parentData)
      case "rxel" => rxelCommand(paramStr, timeoutSec, parentData)
      case "rxm"  => rxmCommand(paramStr, timeoutSec, parentData)
      case "rxml"  => rxmlCommand(paramStr, timeoutSec, parentData)
      case "rx_repl"  => rxReplCommand(paramStr, timeoutSec, parentData)
      case _ => throw new RuntimeException(s"BUG: SMGRegexCommands: Invalid action: $action")
    }
  }
}
