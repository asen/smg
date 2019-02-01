package com.smule.smg.grapher

import com.smule.smg.core.SMGObjectView

/**
 * Created by asen on 11/10/15.
 */
/**
  * Class representing a single SMG image
  *
  * @param obj - SMGObjectView] used to generate the image
  * @param period - period for the image graph
  * @param imageUrl - image url
  * @param remoteId - optional remote id (None if local)
  */
case class SMGImage(obj: SMGObjectView, period: String, imageUrl: String,
                    gopts: GraphOptions, remoteId: Option[String] = None) extends SMGImageView

object SMGImage {
  def errorImage(ov: SMGObjectView, period: String, gopts: GraphOptions, remoteId: Option[String]) =
    SMGImage(ov, period, "/assets/images/error.png", gopts, remoteId)
}
