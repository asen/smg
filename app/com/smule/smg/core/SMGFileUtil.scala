package com.smule.smg.core

import java.io.File
import java.nio.file.Files

import scala.collection.JavaConverters._

object SMGFileUtil {

  def getFileLines(fn: String): Seq[String] = {
    Files.readAllLines(new File(fn).toPath).asScala
  }

  def getFileContents(fn: String): String = {
    Files.readString(new File(fn).toPath)
  }
}

