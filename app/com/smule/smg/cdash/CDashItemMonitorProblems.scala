package com.smule.smg.cdash

import com.smule.smg.core.SMGIndex
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
      <span>
      <span style={ s"background-color: ${ms.severityColor;}" }> {ms.currentStateVal}</span>
        <a href={ ms.showUrl}>{ms.text}</a>
        [ { if(ms.isHard) { <strong>HARD</strong> } else { "SOFT" } }]
      </span>
    }.mkString
  })

  override def linkUrl: Option[String] = Some("/monitor")
}
