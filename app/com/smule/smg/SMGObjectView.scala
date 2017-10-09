package com.smule.smg

/**
  * Created by asen on 11/23/15.
  */
/**
  * A SMG "object" interface, used to display a SMG object. An object is generally represented as a graph in the UI
  */
trait SMGObjectView extends SMGObjectBase {

  // override val id: String
  // override val title: String
  // override val vars: List[Map[String, String]]
  // override def interval: Int

  /**
    * whether the object lines are stacked
    */
  val stack: Boolean

  /**
   * Which vars to include
   */
  val graphVarsIndexes: Seq[Int]

  /**
    * List of Maps for each CDEF variable of this object
    */
  val cdefVars: List[Map[String, String]]

  def filteredVars(inclCdefVars: Boolean) : List[Map[String, String]] = {
    val ovars = for ( (v,i) <- vars.zipWithIndex ;
          // assume empty vars means all vars
          if graphVarsIndexes.isEmpty || graphVarsIndexes.contains(i)) yield v
    if (inclCdefVars) {
      ovars ++ cdefVars
    } else {
      ovars
    }
  }

  override def searchVars: List[Map[String, String]] = filteredVars(true)

  private val SHORT_TITLE_MAX_LEN = 70

  /**
    * Get a shortened version of an object title (to be displayed inside graph images)
    *
    * @return - shortened version of the title if above SHORT_TITLE_MAX_LEN or the title if shorter
    */
  def shortTitle = if (title.length <= SHORT_TITLE_MAX_LEN) title else title.substring(0, SHORT_TITLE_MAX_LEN - 3) + "..."

  /**
    * The "show" url for this object
    *
    * @return - a string representing an url to display this object details
    */
  def showUrl:String

  def dashUrl: String = {
    val arr = SMGRemote.localId(id).split("\\.")
    val rmtId = SMGRemote.remoteId(id)
    val optDot = if (arr.length > 1) "." else ""
    "/dash?px=" + arr.dropRight(1).mkString(".") + optDot + "&sx=" + optDot + arr.lastOption.getOrElse("") +
      (if (rmtId != SMGRemote.local.id) "&remote=" + rmtId else "")
  }

  def parentDashUrl: Option[String] = Some("/dash?px=" + SMGRemote.localId(id).split("\\.").dropRight(1).mkString(".") + "&remote=" + SMGRemote.remoteId(id) )

  /**
    *
    * @return
    */
  def fetchUrl(period: String, step: Option[Int]): String

  val rrdFile: Option[String]

  val isAgg: Boolean

  val refObj: Option[SMGObjectUpdate]

  def inspectUrl: Option[String] = if (isAgg) None else Some(s"/inspect/$id")

  def ouId: String = refObj.map(_.id).getOrElse(id)

  def remoteId: String = SMGRemote.remoteId(id)

}
