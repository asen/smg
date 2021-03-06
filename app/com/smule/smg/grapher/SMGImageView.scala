package com.smule.smg.grapher

import com.smule.smg.core.SMGObjectView
import com.smule.smg.rrd.{SMGRrd, SMGRrdConfig}

/**
  * An interface for an object used to display a single image in SMG
  */
trait SMGImageView {

  /**
    * An object view interface for which this image is graphed
    */
  val obj: SMGObjectView

  /**
    * period for this image
    */
  val period: String

  val gopts: GraphOptions

  /**
    * URL for this image
    */
  val imageUrl: String

  /**
    * optional remote id for this image (None if local)
    */
  val remoteId: Option[String]

  lazy val fetchUrl: String = obj.fetchUrl(period, gopts)

  def resolution(rrdConfig: SMGRrdConfig): String =
    SMGRrd.getDataResolution(obj.interval, period,gopts, obj.rraDef, rrdConfig.dataPointsPerImage)
}
