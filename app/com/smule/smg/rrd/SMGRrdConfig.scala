package com.smule.smg.rrd

import com.smule.smg.core.{SMGCmd, SMGLogger}

/**
  * rrdtool configuration
  *
  * @param rrdTool - path to rrdtool executable
  * @param rrdToolSocket - optional path to rrdtool socket file to be used for updates
  */
case class SMGRrdConfig(rrdTool: String ,
                        rrdToolSocket: Option[String],
                        rrdSocatCommand: String,
                        rrdUpdateBatchSize: Int,
                        rrdGraphWidth: Int,
                        rrdGraphHeight: Int,
                        rrdGraphFont: Option[String],
                        private val dataPointsPerPixel: Int,
                        private val dataPointsPerImageOpt: Option[Int],
                        private val rrdGraphWidthPadding: Option[Int],
                        private val maxArgsLengthOpt: Option[Int]
                       ) {
  private val log = SMGLogger

  val dataPointsPerImage: Int = dataPointsPerImageOpt.getOrElse(rrdGraphWidth * dataPointsPerPixel)

  def flushRrdCachedFile(rrdFile: String): Unit = {
    if (rrdToolSocket.isDefined) {
      log.debug(s"SMGRrdConfig.flushSocket: flushing $rrdFile via " + rrdToolSocket.get)
      try {
        SMGCmd(s"$rrdTool flushcached --daemon ${rrdToolSocket.get} $rrdFile", 120).run
      } catch {
        case t: Throwable => log.ex(t, "Unexpected exception while flushing socket")
      }
    }
  }

  // rrdtool adds padding
  val imageCellWidth: Int = rrdGraphWidth + rrdGraphWidthPadding.getOrElse(SMGRrdConfig.defaultRrdGraphWidthPadding)

  val maxArgsLength: Int = maxArgsLengthOpt.getOrElse(SMGRrdConfig.defaultMaxArgsLength)

  val useBatchedUpdates: Boolean = rrdToolSocket.isDefined && (rrdUpdateBatchSize > 1)
}

object SMGRrdConfig {

  val defaultGraphWidth = 607
  val defaultGraphHeight = 152
  val defaultDataPointsPerPixel = 3
  val defaultRrdGraphWidthPadding = 83 // rrdtool adds 81 + 2 right padding

  // 2621440 according to getconf ARG_MAX on linux and 262144 on Mac, using lower default to be safe
  val defaultMaxArgsLength = 25000
}
