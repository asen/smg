package com.smule.smg.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardCopyOption}

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
//    Files.readString(fle.toPath) // XXX not available in Java 8
    new String(Files.readAllBytes(fle.toPath), StandardCharsets.UTF_8)
  }

  // throw on i/o error
  def outputStringToFile(fname: String, inp: String, backupExt: Option[String]): Unit = {
    val filePath = Paths.get(fname)
    val backupFilePath = backupExt.map { ext =>
      Paths.get(fname.split('.').dropRight(1).mkString(".") + "." + ext)
    }
    val dir = filePath.getParent
    val tempFn = Files.createTempFile(dir, s".tmp-${filePath.getFileName}", "-tmp")
    try {
      if (backupFilePath.isDefined && Files.exists(filePath))
        Files.copy(filePath, backupFilePath.get, StandardCopyOption.REPLACE_EXISTING)
      Files.writeString(tempFn, inp)
      Files.move(tempFn, filePath, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(tempFn)
    }
  }
}

