package com.smule.smgplugins.spiker

import com.smule.smg.SMGObjectView

/**
  * Created by asen on 9/26/16.
  */
case class SMGSpikerAnomaly(obj: SMGObjectView, vix: Int, atype: String, info :String, alert:Boolean) {

  def toHtml: String = {
    val lbl = obj.filteredVars(false)(vix)("label")
    s"<a href='${obj.showUrl}'>${obj.title}</a> (<a href='/dash?px=${obj.id}'>Dash</a>) $lbl ($vix) : $atype : $info" +
      (if (alert) " <font color='red'>ALERT</font>" else "")
  }

  def toTxt(urlPx: Option[String] = None): String = {
    val lbl = obj.filteredVars(false)(vix)("label")
    val px = if (urlPx.isDefined) s"${urlPx.get}/dash?px=" else ""
    s"$px${obj.id}:  $lbl ($vix) : $atype"
  }
}
