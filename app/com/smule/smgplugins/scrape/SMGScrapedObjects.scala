package com.smule.smgplugins.scrape

import com.smule.smg.config.SMGConfIndex
import com.smule.smg.core.{SMGPreFetchCmd, SMGRrdAggObject, SMGRrdObject}
import com.smule.smg.grapher.SMGraphObject

case class SMGScrapedObjects(
                              preFetches: List[SMGPreFetchCmd],
                              rrdObjects: List[SMGRrdObject],
                              viewObjects: List[SMGraphObject],
                              aggObjects: List[SMGRrdAggObject],
                              indexes: List[SMGConfIndex]
                            ){
  lazy val isEmpty: Boolean = rrdObjects.isEmpty && aggObjects.isEmpty && viewObjects.isEmpty

  def merged(other: SMGScrapedObjects): SMGScrapedObjects = SMGScrapedObjects(
    preFetches ++ other.preFetches,
    rrdObjects ++ other.rrdObjects,
    viewObjects ++ other.viewObjects,
    aggObjects ++ other.aggObjects,
    indexes ++ other.indexes
  )
}

