package com.smule.smg.cdash

import scala.collection.JavaConverters._
import scala.collection.mutable

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
    obj.asInstanceOf[java.util.List[Object]].asScala.map(_.toString)
  }.getOrElse(Seq())

  def getDataMap(k: String): Map[String, Object] = data.get(k).map{ obj =>
    obj.asInstanceOf[java.util.Map[String,Object]].asScala.toMap
  }.getOrElse(Map())

  def getDataList(k: String): Seq[Object] = data.get(k).map{ obj =>
    obj.asInstanceOf[java.util.List[Object]].asScala
  }.getOrElse(Seq())

  def asErrorItem(msg: String = ""): CDashItemError = CDashItemError(this, msg = msg)
}

object CDashConfigItem {
  def fromYamlMap(ymap: mutable.Map[String,Object]): CDashConfigItem = {
    val widthStr = ymap.getOrElse("width", "").toString.trim
    val heightStr = ymap.getOrElse("height", "").toString.trim
    CDashConfigItem(
      id = ymap("id").toString,
      itemType = CDashItemType.withName(ymap("type").asInstanceOf[String]),
      title = ymap.get("title").map(_.asInstanceOf[String]),
      width = if (widthStr.isEmpty) None else Some(widthStr),
      height = if (heightStr.isEmpty) None else Some(heightStr),
      data = ymap.toMap
    )
  }
}