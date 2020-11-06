package com.smule.smgplugins.cc.csv

import com.smule.smg.core.SMGLogger

case class SMGCsvValueSelector(
                                rowSelectors: List[SMGCsvSelectorRow],
                                colSelectors: List[SMGCsvSelectorCol]
                              ){
  val invalid: Boolean = rowSelectors.isEmpty || colSelectors.isEmpty
}

object SMGCsvValueSelector {
//  private val log = SMGLogger
  def parse(inp: String): Option[SMGCsvValueSelector] = {
    val tpl = SMGCsvSelectorRow.parseAll(inp)
    if (tpl._1.isEmpty)
      None
    else {
      val cols = SMGCsvSelectorCol.parseAll(tpl._2)
      if (cols.isEmpty)
        None
      else
        Some(SMGCsvValueSelector(
          rowSelectors = tpl._1,
          colSelectors = cols
        ))
    }
  }
}
