package com.smule.smgplugins.cc.rx

import com.smule.smg.core._
import com.smule.smgplugins.cc.shared.{CCStringUtil, SMGCCRunner}

import scala.collection.mutable
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

// :cc rxm [-d <default>] <regex>
//    returns entire input (all lines) if matching, otherwise default (if specified), error otherwise
// :cc rxml [-d <default>] <regex>
//    returns individual matching lines, error if no default and no matching lines (like grep),
//    returns default if nothing matches

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

class SMGRegexCommands(log: SMGLoggerApi) extends SMGCCRunner {

  private def validateParams(action: String, paramStr: String, timeoutSec: Int,
                             parentData: Option[ParentCommandData]): (Regex, String, Map[String,String]) = {
    if (parentData.isEmpty) {
      throwOnError(action, paramStr, timeoutSec, s"Regex commands expect parent data")
    }
    var rem = paramStr.stripLeading()
    val opts = mutable.Map[String,String]()
    while (rem.startsWith("-")){
      val t = CCStringUtil.extractToken(rem)
      val t2 = CCStringUtil.extractToken(t.rem)
      opts.put(t.tkn, t2.tkn)
      rem = t2.rem.stripLeading()
    }
    val (rxStr, rem1) = SMGRegexCommands.delimitedStr(rem)
    val regex = try {
      rxStr.r
    } catch { case t: Throwable =>
      throwOnError(action, paramStr, timeoutSec, s"Invalid regex: ${t.getMessage}")
    }
    (regex, rem1.stripLeading(), opts.toMap)
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

    val (regex, rem, opts) = validateParams("rxe", paramStr, timeoutSec, parentData)
    val mgroups = getMatchGroups(rem)
    val inp = parentData.get.res.asStr
    val strLst = getAllMatches(regex, inp, mgroups)
    if (strLst.isEmpty)
      throwOnError("rxe", paramStr, timeoutSec, s"Regex did not match (${regex}): $inp")
    CommandResultListString(strLst.toList, None)
  }

  private def rxelCommand(paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem, opts) = validateParams("rxel", paramStr, timeoutSec, parentData)
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

  private def getDefaultOpt(opts: Map[String, String]): Option[String] = {
    var default : Option[String] = None
    opts.foreach { case (k,v) =>
      k match {
        case "-d" | "--default" => default = Some(v)
        case x => log.warn(s"SMGRegexCommands: Invalid rxml option (ignored): ${x}")
      }
    }
    default
  }

  private def rxmCommand(paramStr: String, timeoutSec: Int,
                 parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem, opts) = validateParams("rxm", paramStr, timeoutSec, parentData)
    val inp = parentData.get.res.asStr
    val default = getDefaultOpt(opts)
    if (!regexMatches(regex, inp)) {
      if (default.isDefined)
        CommandResultListString(List(default.get), None)
      else
        throwOnError("rxm", paramStr, timeoutSec, s"Regex did not match (${regex}): $inp")
    } else
      parentData.get.res
  }

  private def rxmlCommand(paramStr: String, timeoutSec: Int,
                 parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem, opts) = validateParams("rxml", paramStr, timeoutSec, parentData)
    val parentRes = parentData.get.res
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    val default = getDefaultOpt(opts)
    val strLst = inpLines.filter(ln => regexMatches(regex, ln))
    if (strLst.isEmpty) {
      if (default.isDefined)
        CommandResultListString(List(default.get), None)
      else
        throwOnError("rxml", paramStr, timeoutSec,
          s"Regex did not match (${regex}): $inpLines")
    } else
      CommandResultListString(strLst, None)
  }

  // regex replace, sed-like
  private def rxReplCommand(paramStr: String, timeoutSec: Int,
                            parentData: Option[ParentCommandData]): CommandResult = {

    val (regex, rem, opts) = validateParams("rx_repl", paramStr, timeoutSec, parentData)
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

  def runCommand(action: String, paramStr: String, timeoutSec: Int,
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
