package com.smule.smg.core

import java.util

import com.smule.smg.notify.SMGMonNotifyConf
import com.smule.smg.rrd.{SMGRraDef, SMGRrd}

/**
  * An object representing a single rrd database
 *
  * @param id - object id. by convention should have a class.object.value format
  * @param command - a SMG command to fetch the values for this object vars.
  * @param vars - a list of Maps each representing a variable description
  * @param title - object human readable title
  * @param rrdType - rrdtool graph type - GAUGE, COUNTER, etc
  * @param interval - update interval for this object
  * @param stack - whether to graph the lines of this object stacked
  * @param preFetch - a pre-fetch command id, to be invoked before this (and other rrdObjects sharing the same id)
  *                 object's fetch command
  */
case class SMGRrdObject(id: String,
                        parentIds: Seq[String],
                        command: SMGCmd,
                        vars: List[SMGObjectVar],
                        title: String,
                        rrdType: String,
                        interval: Int,
                        override val dataDelay: Int,
                        delay: Double,
                        stack: Boolean,
                        preFetch: Option[String],
                        rrdFile: Option[String],
                        rraDef: Option[SMGRraDef],
                        override val rrdInitSource: Option[String],
                        notifyConf: Option[SMGMonNotifyConf],
                        labels: Map[String,String]
                       ) extends SMGObjectUpdate with SMGFetchCommand {

//  private val log = SMGLogger

//  private val nanList: List[Double] = vars.map(v => Double.NaN)

//  private var myPrevCacheTs: Int = 0
//  private var myPrevCachedValues: List[Double] = nanList
//
//  private var myCacheTs: Int = SMGRrd.tssNow
//  private var myCachedValues = nanList

  override val graphVarsIndexes: List[Int] = vars.indices.toList

  override val cdefVars: List[SMGObjectVar] = List()

  override val pluginId: Option[String] = None

  override val passData: Boolean = true
  override val ignorePassedData: Boolean = false

  val commandDesc: Option[String] = Some(title)

  override val searchText: String = super.searchText + " " + cmdSearchText
}
