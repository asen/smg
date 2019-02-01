package com.smule.smg.rrd

import com.smule.smg.grapher.SMGAggObjectView


class SMGRrdFetchAgg(val rrdConf: SMGRrdConfig, val aggobj: SMGAggObjectView) {

  import SMGRrd._

  def fetch(params: SMGRrdFetchParams): List[SMGRrdRow] = {
    val objsData = for ( o <- aggobj.objs ) yield {
      new SMGRrdFetch(rrdConf, o).fetch(params)
    }
    val ret = merge(aggobj.op, objsData)
    reCalcResolution(ret, params.resolution)
  }

  private def merge(op: String, inp: Seq[List[SMGRrdRow]]) : List[SMGRrdRow] = {
    val byTs = inp.flatten.groupBy(_.tss)
    byTs.keys.toList.sorted.map { ts =>
      val toMerge = byTs(ts)
      val nums = toMerge.toList.map(_.vals)
      val rowVals = mergeValues(op, nums)
      SMGRrdRow(ts, rowVals)
    }
  }
}

