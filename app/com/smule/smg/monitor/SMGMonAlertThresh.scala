package com.smule.smg.monitor

import com.smule.smg.core.SMGLogger

case class SMGMonAlertThresh(value: Double, op: String) {

  def checkAlert(fetchedValue: Double, numFmt: (Double) => String):Option[String] = {
    op match {
      case "gte" => if (fetchedValue >= value) Some(s"${numFmt(fetchedValue)} >= ${numFmt(value)}") else None
      case "gt"  => if (fetchedValue > value) Some(s"${numFmt(fetchedValue)} > ${numFmt(value)}") else None
      case "lte" => if (fetchedValue <= value) Some(s"${numFmt(fetchedValue)} <= ${numFmt(value)}") else None
      case "lt"  => if (fetchedValue < value) Some(s"${numFmt(fetchedValue)} < ${numFmt(value)}") else None
      case "eq"  => if (fetchedValue == value) Some(s"${numFmt(fetchedValue)} == ${numFmt(value)}") else None
      case "neq"  => if (fetchedValue == value) Some(s"${numFmt(fetchedValue)} != ${numFmt(value)}") else None
      case badop: String => {
        SMGLogger.warn("SMGMonAlertThresh: invalid op: " + badop)
        None
      }
    }
  }
}

