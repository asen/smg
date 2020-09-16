package com.smule.smg.config

import java.io.File
import java.security.MessageDigest

import scala.util.Try

case class DirLevelsDef(lvls: Array[Int]) {
  def getHashLevelsPath(oid: String): String = {
    val hash: Array[Byte] = MessageDigest.getInstance("MD5").digest(oid.getBytes)
    var curOffs = 0
    val hashDir = lvls.map { case (lvcount) =>
      val bytes = hash.slice(curOffs, curOffs + lvcount)
      curOffs += lvcount
      if (bytes.isEmpty)
        ""
      else {
        bytes.map("%02x".format(_)).mkString + File.separator
      }
    }.mkString
    hashDir
  }
}

object DirLevelsDef {
  def parse(in: String): Option[DirLevelsDef] = {
    val arr = in.split(":").map(s => Try(s.toInt).getOrElse(0))
    if (arr.exists(_ <= 0))
      None
    else
      Some(DirLevelsDef(arr))
  }
}
