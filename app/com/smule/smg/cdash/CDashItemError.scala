package com.smule.smg.cdash

case class CDashItemError(conf: CDashConfigItem, msg: String = "", linkUrl: Option[String] = None) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.Error

  override def htmlContent: String = {
    <p>
      <font color="red">Error: {msg}</font>
    </p>
  }.mkString
}
