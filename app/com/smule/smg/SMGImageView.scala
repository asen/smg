package com.smule.smg

/**
  * Created by asen on 11/24/15.
  */

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

  /**
    * URL for this image
    */
  val imageUrl: String

  /**
    * optional remote id for this image (None if local)
    */
  val remoteId: Option[String]

  def fetchUrl = obj.fetchUrl(period)

  def resolution(step: Option[Int]) = SMGRrd.getDataResolution(obj.interval, period, step)
}
