package controllers

import javax.inject.Inject
import play.api.http.{DefaultHttpFilters, EnabledFilters}
/**
  * Created by asen on 11/16/15.
  */
class SMGHttpFilters @Inject()(
                                defaultFilters: EnabledFilters,
                                log: LoggingFilter
                              ) extends DefaultHttpFilters(defaultFilters.filters :+ log: _*)

