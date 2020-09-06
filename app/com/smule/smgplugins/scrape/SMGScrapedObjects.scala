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
                            )

