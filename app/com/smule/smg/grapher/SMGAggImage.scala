package com.smule.smg.grapher

/**
  * Created by asen on 11/24/15.
  */
/**
  * An object representing an image produced from a SMG aggregate object (SMGAggobject)
  * @param obj - the aggregate object
  * @param period - period covered in the image
  * @param imageUrl - http url for the image
  * @param remoteId - optional remote id for the image. None if local
  */
case class SMGAggImage (obj: SMGAggObjectView, period: String,
                        imageUrl: String, gopts: GraphOptions,
                        remoteId: Option[String] = None) extends SMGImageView
