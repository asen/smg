package com.smule.smg.cdash

case class CDashItemExternalImage(conf: CDashConfigItem, src: String) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.ExternalImage

  override lazy val linkUrl: Option[String] = conf.getDataStr("link")
  private val alt = conf.getDataStr("alt")
  private val imgHeight = conf.getDataStr("img_height")
  private val imgWidth = conf.getDataStr("img_width")
  private val linkOpenTag = linkUrl.map(s => s"<a href='$s'>").getOrElse("")
  private val linkCloseTag = linkUrl.map(s => s"</a>").getOrElse("")
  override def htmlContent: String = {
    val heightS = imgHeight.map(s => s" height='${s}'").getOrElse("")
    val widthS = imgWidth.map(s => s" width='${s}'").getOrElse("")
    val altS = alt.getOrElse(src)
    linkOpenTag +
      s"<img src='$src' alt='${altS}' title='${altS}'${heightS}${widthS} />" +
      linkCloseTag
  }
}
