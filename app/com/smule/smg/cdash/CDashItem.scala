package com.smule.smg.cdash

trait CDashItem {
  val itemType: CDashItemType.Value
  val conf: CDashConfigItem

  lazy val id: String = conf.id
  lazy val titleStr: String = conf.title.getOrElse(id)
  lazy val widthStr: String = conf.width.map(s => if (s.matches("^\\d+$")) s + "px" else s).getOrElse("")
  lazy val heightStr: String = conf.height.map(s => if (s.matches("^\\d+$")) s + "px" else s)getOrElse("")

  def linkUrl: Option[String]
  def htmlContent: String
//  = {
//    <p>Not implemented: {this.toString}</p>
//  }.mkString

  protected def htmlContentList[T](seq: Seq[T], elemHtmlFn: (T) => String): String = {
    <ul class="cdash-list">
      {
      seq.map { itm =>
        <li class="cdash-list-item">{ scala.xml.Unparsed(elemHtmlFn(itm)) }</li>
      }
      }

    </ul>
  }.mkString

}
