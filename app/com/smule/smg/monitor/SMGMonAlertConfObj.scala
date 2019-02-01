package com.smule.smg.monitor

import scala.collection.mutable.ListBuffer

import com.smule.smg._

/**
  * Created by asen on 7/7/16.
  */

case class SMGMonAlertConfObj(varConfs: Map[Int, Seq[SMGMonAlertConfVar]]) {
  def varConf(ix: Int): Seq[SMGMonAlertConfVar] = varConfs.getOrElse(ix, Seq())
}
