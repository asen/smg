package com.smule.smg.cdash

import com.smule.smg.core.SMGIndex

case class CDashItemIndexStates(conf: CDashConfigItem, imgWidth: String, ixes: Seq[SMGIndex]) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.IndexStates

  override def htmlContent: String = {
    <div>
      {
      ixes.map { ix =>
        <div align="right">{ ix.title }: <img src={ s"/monitor/indexSvg?ixid=${ix.id}&w=$imgWidth" }></img></div>
      }
      }
    </div>
  }.mkString
}
