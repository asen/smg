package com.smule.smg.cdash

import com.smule.smg.monitor.{SMGMonitorLogFilter, SMGMonitorLogMsg}

case class CDashItemMonitortLog(conf: CDashConfigItem,
                                flt: SMGMonitorLogFilter,
                                logs: Seq[SMGMonitorLogMsg]
                       ) extends CDashItem {
  override val itemType: CDashItemType.Value = CDashItemType.MonitorLog

  override def htmlContent: String = htmlContentList(logs, {ms: SMGMonitorLogMsg =>
    {
      <span>
        [ { ms.remote.name }]
        [{ms.tsFmt}]: <span class={ s"ml-type-${ms.mltype}" }>{ms.mltype}</span>{if (ms.ouids.nonEmpty) {
        <a href="/dash?@{ms.objectsFilterWithRemote}">
          {ms.msIdFmt}
        </a>
      } else {
        {
          ms.msIdFmt
        }
      }}
        (n={ms.repeat},
         { if(ms.isHard){ <strong>HARD</strong> } else { "SOFT" } }
      <span class="ml-msg">{ms.msg}</span>
      </span>
    }.mkString
  })

  override def linkUrl: Option[String] = Some("/monitor/log")

}
