package com.smule.smgplugins.spiker

import scala.collection.mutable.ListBuffer

/**
  * Created by asen on 9/26/16.
  */
object SpikeDetector {

  def calcMeanStddev(data: Seq[Double]): (Double, Double) = {
    val count = data.length
    val mean = data.sum / count
    val devs = data.map(d => (d - mean) * (d - mean))
    val stddev = Math.sqrt(devs.sum / count)
    (mean, stddev)
  }

  private def isSpike(p: SpikeCheckParams,
                      sMean: Double,
                      sStddev: Double,
                      aMean: Double,
                      aStddev: Double,
                      rMean: Option[Double],
                      rStddev: Option[Double]
                     ): Option[String] = {
    val errors = ListBuffer[String]()

    if (sMean > (p.spikeK * aMean)) {
      // possible spike
      if ((sStddev > p.minStddev) && (sStddev > (p.spikeK * aStddev))){
        // looks like a spike ... check if regular
        val spikeStr = s"SPIKE: mean $sMean gt ${p.spikeK} * $aMean, stddev $sStddev gt ${p.spikeK} * $aStddev"
//        var errAppend = ": Reg Check Disabled"
        if (rMean.isDefined && rStddev.isDefined) {
           if ((sMean > (p.spikeK * rMean.get)) && (sStddev > (p.spikeK * rStddev.get))) {
             // non regular spike
             errors += spikeStr + s", reg_check mean $sMean gt ${p.spikeK} * ${rMean.get}" +
               s", reg_check stddev $sStddev > ${p.spikeK} * ${rStddev.get}"
           }
        } else {
          errors += spikeStr + ", reg_check DISABLED"
        }
      }
    }

    if (sMean < (aMean / p.spikeK)) {
      // possible drop
      if ((sStddev > p.minStddev) && (sStddev > (p.spikeK * aStddev))){
        // looks like a spike ... check if regular
        val spikeStr = s"DROP: mean $sMean lt $aMean / ${p.spikeK}, stddev $sStddev gt ${p.spikeK} * $aStddev"
        //        var errAppend = ": Reg Check Disabled"
        if (rMean.isDefined && rStddev.isDefined) {
          if ((sMean < (rMean.get / p.spikeK)) && (sStddev > (p.spikeK * rStddev.get))) {
            // non regular spike
            errors += spikeStr + s", reg_check mean $sMean lt ${rMean.get} / ${p.spikeK}" +
              s", reg_check stddev $sStddev gt ${p.spikeK} * ${rStddev.get}"
          }
        } else {
          errors += spikeStr + ", reg_check DISABLED"
        }
      }
    }

    if (errors.isEmpty) None else Some(errors.mkString(" "))
  }

  def checkSpike(p: SpikeCheckParams, data: Seq[Double]): Option[String] = {
    val dataLen = data.length
    if (dataLen < p.minDataPoints) {
      return None
    }
    val sampleData = data.drop(dataLen - p.sampleSize)
    val (sampleMean, smpleStddev) = calcMeanStddev(sampleData)
    val restOfData = data.take(dataLen - p.sampleSize)
    val (restOfDataMean, restOfDataStddev) = calcMeanStddev(restOfData)
    val (regMean, regStddev) = if (p.regOffset.isDefined && p.regSampleSize.isDefined) {
      val ro = dataLen - p.regOffset.get
      val regSampleData = data.slice(ro, ro + p.regSampleSize.get)
      val rt = calcMeanStddev(regSampleData)
      (Some(rt._1), Some(rt._2))
    } else (None, None)
    isSpike(p, sampleMean, smpleStddev, restOfDataMean, restOfDataStddev, regMean, regStddev)
  }

}
