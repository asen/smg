package com.smule.smg.core

import java.io.File
import java.nio.file.Files

import scala.collection.JavaConverters._

object SMGFileUtil {

  def getFileLines(fn: String): Seq[String] = {
    getFileLines(new File(fn))
  }

  def getFileLines(fle: File): Seq[String] = {
    Files.readAllLines(fle.toPath).asScala
  }

  def getFileContents(fn: String): String = {
    getFileContents(new File(fn))
  }

  def getFileContents(fle: File): String = {
    Files.readString(fle.toPath)
  }

}

