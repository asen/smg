package com.smule.smg.monitor

import com.smule.smg.SMGRemote

case class SMGMonitorStatesResponse(remote: SMGRemote, states: Seq[SMGMonState], isMuted: Boolean)

