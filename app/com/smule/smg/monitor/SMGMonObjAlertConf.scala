package com.smule.smg.monitor

import scala.collection.mutable.ListBuffer

import com.smule.smg._

/**
  * Created by asen on 7/7/16.
  */

case class SMGMonObjAlertConf(varConfs: Map[Int, Seq[SMGMonVarAlertConf]]) {
  def varConf(ix: Int): Seq[SMGMonVarAlertConf] = varConfs.getOrElse(ix, Seq())
}
