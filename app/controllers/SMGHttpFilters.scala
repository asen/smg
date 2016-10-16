package controllers

import javax.inject.Inject

import play.api.mvc.EssentialFilter
import play.http.HttpFilters

/**
  * Created by asen on 11/16/15.
  */
class SMGHttpFilters @Inject()(
                          log: LoggingFilter
                        ) extends HttpFilters {
  override def filters(): Array[EssentialFilter] = Array(log)
}
