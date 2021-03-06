package com.smule.smg.cdash

import com.smule.smg.config.SMGStringUtils
import com.smule.smg.monitor.{SMGMonFilter, SMGMonState, SMGMonitorStatesResponse}

case class CDashItemMonitorProblems(conf: CDashConfigItem,
                                    flt: SMGMonFilter,
                                    msr: Seq[SMGMonitorStatesResponse]
                          ) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.MonitorProblems

  private def sortBySeverity: Seq[SMGMonState] = msr.flatMap(r => r.states).
    sortBy(ms => (- ms.severity, ms.remote.id))

  override def htmlContent: String = htmlContentList(sortBySeverity, {ms: SMGMonState =>
    {
      <span>[<b>{ms.remote.name}</b>]
      <span style={ s"background-color: ${ms.severityColor;}" }> {ms.currentStateVal}</span>
        <a href={ms.showUrl}>{SMGStringUtils.ellipsifyAt(ms.text, 160)}</a>
        [ { if(ms.isHard) { <strong>HARD</strong> } else { "SOFT" } }]
      </span>
    }.mkString
  })

  override def linkUrl: Option[String] = Some("/monitor")
}
