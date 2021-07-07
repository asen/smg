package com.smule.smg.cdash

case class CDashItemLinksPanel(conf: CDashConfigItem, links: Seq[Map[String,String]]) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.LinksPanel

  override def linkUrl: Option[String] = None

  private def htmlContentLink(url: String, text: String, target: Option[String]): String = {
    val tgtSTr = if (target.isEmpty) "" else s" target='${target.get}'"
    s"<a$tgtSTr href='${url}'>${text}</a>"
  }

  override def htmlContent: String = htmlContentList(links, (m: Map[String,String]) => {
      val url = m.getOrElse("url", "INVALID_URL")
      val text = m.getOrElse("text", url)
      val target = m.get("target")
      htmlContentLink(url, text, target)
    })
}
