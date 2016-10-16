package controllers

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by asen on 11/11/15.
  */

import play.api.Logger
import play.api.mvc._

/**
  * Play 2.4 access logging filter
  */
class LoggingFilter extends EssentialFilter {

  val log =  Logger("access")

  def apply(nextFilter: EssentialAction) = new EssentialAction {
    def apply(requestHeader: RequestHeader) = {

      val startTime = System.currentTimeMillis

      nextFilter(requestHeader).map { result =>

        val endTime = System.currentTimeMillis
        val requestTime = endTime - startTime

        log.info(s"${requestHeader.method} ${requestHeader.uri}" +
          s" took ${requestTime}ms and returned ${result.header.status}")
        result.withHeaders("Request-Time" -> requestTime.toString)

      }
    }
  }
}

