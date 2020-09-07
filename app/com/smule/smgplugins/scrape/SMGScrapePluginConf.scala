package com.smule.smgplugins.scrape

case class SMGScrapePluginConf(targets: Seq[SMGScrapeTargetConf],
                               confOutputDir: Option[String],
                               confOutputDirOwned: Boolean) {
}
