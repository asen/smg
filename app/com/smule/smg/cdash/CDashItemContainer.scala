package com.smule.smg.cdash

case class CDashItemContainer(conf: CDashConfigItem, items: Seq[CDashItem]) extends CDashItem  {
  override val itemType: CDashItemType.Value = CDashItemType.Container

  override def linkUrl: Option[String] = None

  override def htmlContent: String = "UNUSED"
}
