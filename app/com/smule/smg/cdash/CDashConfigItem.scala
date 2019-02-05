package com.smule.smg.cdash

import scala.collection.JavaConversions._

case class CDashConfigItem(
                            id: String,
                            itemType: CDashItemType.Value,
                            title: Option[String],
                            width: Option[String],
                            height: Option[String],
                            data: Map[String,Object]
                          ) {

  def getDataStr(k: String): Option[String] = data.get(k).map(_.toString)

  def getDataStrSeq(k: String) : Seq[String] = data.get(k).map{ obj =>
    obj.asInstanceOf[java.util.List[Object]].toSeq.map(_.toString)
  }.getOrElse(Seq())

  def asErrorItem(msg: String = ""): CDashItemError = CDashItemError(this, msg = msg)
}
