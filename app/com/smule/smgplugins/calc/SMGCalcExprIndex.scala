package com.smule.smgplugins.calc

case class SMGCalcExprIndex(id: String,
                            expr: String,
                            title: Option[String],
                            period: Option[String],
                            step: Option[Int],
                            maxy: Option[Double],
                            miny: Option[Double],
                            dpp: Boolean,
                            d95p: Boolean
                           )
