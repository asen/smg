package com.smule.smg.monitor

case class SMGMonHeatmap(lst: List[SMGMonState], statesPerSquare: Int)

object SMGMonHeatmap {
  def join(lst: Seq[SMGMonHeatmap]): SMGMonHeatmap = {
    val newMsLst = lst.flatMap(_.lst)
    val newSps = if (lst.isEmpty) 1 else {
      lst.map(_.statesPerSquare).sum / lst.size
    }
    SMGMonHeatmap(newMsLst.toList, newSps)
  }
}
