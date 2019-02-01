package com.smule.smg.monitor

import com.smule.smg.remote.SMGRemote

case class SMGMonitorStatesResponse(remote: SMGRemote, states: Seq[SMGMonState], isMuted: Boolean)

