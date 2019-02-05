package com.smule.smg.cdash

import scala.concurrent.Future

trait CDashboardApi {
  
  def getDashboardData(cdid: String): Future[Option[CDashboardData]]
}
