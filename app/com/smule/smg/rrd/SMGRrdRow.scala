package com.smule.smg.rrd

/**
  * Created by asen on 12/8/15.
  */

/**
  * An object representing a line returned from rrdtool fetch
  * @param tss - unix timestamp
  * @param vals - list of (Double) values fetched
  */
case class SMGRrdRow(tss: Int, vals: List[Double])
