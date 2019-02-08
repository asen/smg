package com.smule.smg.cdash

case class CDashItemExternal(conf: CDashConfigItem, url: String) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.External

  override def htmlContent: String = {
    <iframe height={heightStr} width={widthStr} src={ url } frameborder="0">{ url }</iframe>
  }.mkString

  override def linkUrl: Option[String] = Some(url)
}


