package com.smule.smg.auth.system

import com.smule.smg.auth.system.cidr.CIDRUtils
import com.smule.smg.core.SMGLogger

case class IpNetwork(ipOrCidr: String) {

  private val log = SMGLogger

  val cidr: String = if (ipOrCidr.contains("/")) ipOrCidr else {
    if (ipOrCidr.contains(":"))
      s"$ipOrCidr/128"
    else
      s"$ipOrCidr/32"
  }

  private val cidrUtil = new CIDRUtils(cidr)

  def isInRange(ip: String): Boolean = {
    try {
      cidrUtil.isInRange(ip)
    } catch { case t: Throwable =>
      log.error(s"Unexpected error in IpNetwork($cidr).isInRange($ip), swallowed", t)
      false
    }
  }
}
