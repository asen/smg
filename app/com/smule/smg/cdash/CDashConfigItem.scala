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

  def getDataMap(k: String): Map[String, Object] = data.get(k).map{ obj =>
    obj.asInstanceOf[java.util.Map[String,Object]].toMap
  }.getOrElse(Map())

  def getDataList(k: String): Seq[Object] = data.get(k).map{ obj =>
    obj.asInstanceOf[java.util.List[Object]].toSeq
  }.getOrElse(Seq())

  def asErrorItem(msg: String = ""): CDashItemError = CDashItemError(this, msg = msg)
}

object CDashConfigItem {
  def fromYamlMap(ymap: Map[String,Object]) = CDashConfigItem(
    id = ymap("id").toString,
    itemType = CDashItemType.withName(ymap("type").toString),
    title = ymap.get("title").map(_.toString),
    width = ymap.get("width").map(_.toString),
    height = ymap.get("height").map(_.toString),
    data = ymap
  )
}