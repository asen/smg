package com.smule.smg.cdash

case class CDashItemExternal(conf: CDashConfigItem, url: String) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.External

  lazy val frameHeightStr: String = conf.getDataStr("fheight").getOrElse(heightStr)
  lazy val frameWidthStr: String = conf.getDataStr("fwidth").getOrElse(widthStr)

  override def htmlContent: String = {
    <iframe height={frameHeightStr} width={frameWidthStr} src={ url } frameborder="0">{ url }</iframe>
  }.mkString

  override def linkUrl: Option[String] = Some(url)
}


