package com.smule.smgplugins.calc

import com.smule.smg.cdash.{CDashConfigItem, CDashItem, CDashItemType}
import com.smule.smg.grapher.SMGImageView

case class CDashItemCalc(conf: CDashConfigItem,
                         ix: String,
                         img: SMGImageView) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.Plugin

  override def htmlContent: String = {
    {<img src={img.imageUrl} alt={img.imageUrl} />}.mkString
  }

  override def linkUrl: Option[String] = Some(s"/plugin/calc?ix=$ix")
}
