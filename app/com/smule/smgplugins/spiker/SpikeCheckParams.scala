package com.smule.smgplugins.spiker

/**
  * Created by asen on 9/26/16.
  */
case class SpikeCheckParams(
                             spikeK: Double,
                             minStddev: Double,
                             sampleSize: Int,
                             regOffset: Option[Int],
                             regSampleSize: Option[Int],
                             minDataPoints: Int
                           )
