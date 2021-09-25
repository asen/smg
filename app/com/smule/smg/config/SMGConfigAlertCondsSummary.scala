package com.smule.smg.config

import com.smule.smg.config.SMGConfigAlertCondsSummary.{IndexAlertCondSummary, ObjectAlertCondSummary}
import com.smule.smg.monitor.{SMGMonAlertConfSource, SMGMonAlertConfVar}

case class SMGConfigAlertCondsSummary(
                                    remoteId: Option[String],
                                    indexConfs: Seq[IndexAlertCondSummary],
                                    objectConfs: Seq[ObjectAlertCondSummary],
                                    errMsg: Option[String]
                                  )


object SMGConfigAlertCondsSummary {

  case class IndexAlertCondSummary(
                                    isHidden: Boolean,
                                    indexId: String,
                                    fltDesc: String,
                                    threshDesc: String,
                                    numOids: Int,
                                    numVars: Int,
                                    sampleOids: Seq[String]
                                  )

  case class ObjectAlertCondSummary(
                                     threshDesc: String,
                                     numOids: Int,
                                     numVars: Int,
                                     sampleOids: Seq[String]
                                   )
  
  private def indexAlertCondSummary(conf: SMGLocalConfig, in: Iterable[(SMGMonAlertConfVar, String, Int)]): Seq[IndexAlertCondSummary] = {
    in.groupBy { t => t._1 }.map { tt =>
      val allVarOids = tt._2.map(_._2).toSeq
      val allObjOids = allVarOids.distinct
      val isHidden = tt._1.src == SMGMonAlertConfSource.HINDEX
      val idxId = tt._1.srcId
      val fltDesc = (if (!isHidden)
        conf.indexesById
      else
        conf.hiddenIndexes).get(idxId).map(_.flt.humanText).getOrElse("undefined")
      IndexAlertCondSummary(
        isHidden = isHidden,
        indexId = idxId,
        fltDesc = fltDesc,
        threshDesc = tt._1.threshDesc((x: Double) => x.toString),
        numOids = allObjOids.size,
        numVars = allVarOids.size,
        sampleOids = allObjOids.take(10)
      )
    }.toSeq
  }

  private def objectAlertCondSummary(conf: SMGLocalConfig, in: Iterable[(SMGMonAlertConfVar, String, Int)]): Seq[ObjectAlertCondSummary] = {
    in.flatMap { t3 =>
      conf.updateObjectsById.get(t3._2).flatMap { ou =>
        def numFmt(d: Double) = {
          ou.numFmt(d, t3._3)
        }
        ou.vars.lift(t3._3).map { vmap =>
          val threshDesc = t3._1.threshDesc(numFmt)
          val myGbKey = Seq(
            vmap.label.map(x => ("label", x)),
            vmap.mu.map(x => ("mu", x)),
            Some(("vix", t3._3.toString)),
            Some(("conf", threshDesc))
          ).flatten.map {t => s"${t._1}=${t._2}"}.mkString(" ")
          (myGbKey, t3._2)
        }
      }
    }.groupBy(_._1).map{ x =>
      val allVarOids = x._2.map(_._2).toSeq
      val allObjOids = allVarOids.distinct
      val addDesc = if (allObjOids.lengthCompare(1) > 0)
        SMGStringUtils.commonPxSxAddDesc(allObjOids) + ", "
      else
        "Single object, "
      ObjectAlertCondSummary(
        threshDesc = addDesc + x._1,
        numOids = allObjOids.size,
        numVars = allVarOids.size,
        sampleOids = allObjOids.take(10)
      )
    }.toSeq
  }

  def build(conf: SMGLocalConfig): SMGConfigAlertCondsSummary = {
    // get a flat list of all configured value alert thresholds
    // and separate by source - object def vs index/hidden index def
    val seqs = conf.objectAlertConfs.flatMap { case (oid, aco) =>
      aco.varConfs.flatMap { case (vix, varConfSeq) =>
        varConfSeq.map { varConf =>
          (varConf, oid, vix)
        }
      }
    }.partition(t => t._1.src != SMGMonAlertConfSource.OBJ)

    val indexSeq = indexAlertCondSummary(conf, seqs._1)
    val obsSeq = objectAlertCondSummary(conf, seqs._2)

    SMGConfigAlertCondsSummary(
      remoteId = None, // only set on deserialization from remote
      indexSeq,
      obsSeq,
      errMsg = None
    )
  }
}