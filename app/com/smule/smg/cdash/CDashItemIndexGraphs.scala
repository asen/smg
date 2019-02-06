package com.smule.smg.cdash

import com.smule.smg.core.SMGIndex
import com.smule.smg.grapher.SMGImageView

case class CDashItemIndexGraphs(
                                 conf: CDashConfigItem,
                                 ix: SMGIndex,
                                 graphs: Seq[SMGImageView]
                               ) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.IndexGraphs

  override def htmlContent: String = {
    graphs.map { img =>
      {<a href={img.obj.dashUrl}><img src={img.imageUrl} alt={img.imageUrl} /></a>}.mkString
    }.mkString("\n")
  }
}
