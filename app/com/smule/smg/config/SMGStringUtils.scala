package com.smule.smg.config

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

object SMGStringUtils {

  private def getLcp(x: List[String], y:List[String]): List[String] = {
    if (x.isEmpty || y.isEmpty|| (x.head != y.head)) {
      List()
    } else {
      x.head :: getLcp(x.tail, y.tail)
    }
  }


  private def commonPrefix(sep: Char, s: String, t: String, out: String = ""): String = {
    val sl = s.split(sep)
    val tl = t.split(sep)
    getLcp(sl.toList, tl.toList).mkString(sep.toString)
  }

  @tailrec
  def commonListPrefix(sep: Char, lst: Seq[String], soFar: Option[String] = None): String = {
    if (lst.isEmpty) soFar.getOrElse("")
    else {
      val cp = commonPrefix(sep, soFar.getOrElse(lst.head), lst.head)
      commonListPrefix(sep, lst.tail, Some(cp))
    }
  }

  private def commonSuffix(sep: Char, s: String, t: String, out: String = ""): String = {
    commonPrefix(sep, s.reverse, t.reverse).reverse
  }

  @tailrec
  def commonListSuffix(sep: Char, lst: Seq[String], soFar: Option[String] = None): String = {
    if (lst.isEmpty) soFar.getOrElse("")
    else {
      val cp = commonSuffix(sep, soFar.getOrElse(lst.head), lst.head)
      commonListSuffix(sep, lst.tail, Some(cp))
    }
  }

  def commonPxSxAddDesc(oids: Seq[String]): String = {
    val addDesc = ListBuffer[String]()
    val commonPrefix = SMGStringUtils.commonListPrefix('.', oids)
    if (commonPrefix != "")
      addDesc += s"Common prefix: $commonPrefix"
    val commonSuffix = SMGStringUtils.commonListSuffix('.', oids)
    if (commonSuffix != "")
      addDesc += s"Common suffix: $commonSuffix"
    if (addDesc.isEmpty)
      addDesc += "No common prefix/suffix"
    addDesc.mkString(", ")
  }

  def ellipsifyAt(s: String, ellipsifyAt: Int): String = if (s.length > ellipsifyAt) {
    s.take(ellipsifyAt - 3) + "..."
  } else s
}
