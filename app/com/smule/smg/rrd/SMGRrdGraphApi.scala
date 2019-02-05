package com.smule.smg.rrd

import com.smule.smg.grapher.GraphOptions

trait SMGRrdGraphApi {

  def graph(outfn:String, period: String, gopts: GraphOptions): Unit
}
