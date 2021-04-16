package com.smule.smgplugins.autoconf

import java.io.File
import java.nio.file.Paths

case class SMGAutoConfPluginConf(
                                  targets: Seq[SMGAutoTargetConf],
                                  templateDirs: Seq[String],
                                  confOutputDir: Option[String],
                                  confOutputDirOwned: Boolean,
                                  preventTemplateReload: Boolean
                                ) {

 def getTemplateFilename(shortName: String): String = {
    var retName = shortName
    if (!retName.contains("."))
      retName += ".yml.ssp"
    val path = Paths.get(retName)
    if (path.isAbsolute)
      return path.toString
    for (td <- templateDirs) {
      val tdName = td.stripSuffix(File.separator) + File.separator + retName
      if (new File(tdName).exists())
        return tdName
    }
    retName
  }
}

object SMGAutoConfPluginConf {
  val empty: SMGAutoConfPluginConf =
    SMGAutoConfPluginConf(targets = Seq(), templateDirs = Seq(),
      confOutputDir = None, confOutputDirOwned = false, preventTemplateReload = false)
}
