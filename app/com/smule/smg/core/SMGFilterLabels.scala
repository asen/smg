package com.smule.smg.core

import scala.util.matching.Regex

case class SMGFilterLabels(name: String, op: Option[String], operand: Option[String], positive: Boolean){
  private lazy val nameRegex: Option[Regex] = if (name.startsWith("()") && (name.lengthCompare(2) > 0))
    SMGFilter.ciRegex(Some(name.drop(2))) else None

  private lazy val operandRegex: Option[Regex] = SMGFilter.ciRegex(operand)

  private def matchesName(labelName: String): Boolean = {
    nameRegex.map(_.findFirstIn(labelName).isDefined).getOrElse(name == labelName)
  }

  private def matchesValue(labelValue: String): Boolean = {
    if (op.isDefined){
      op.get match {
        case "=" => operand.getOrElse("") == labelValue
        case "!=" => operand.getOrElse("") != labelValue
        case "=~" => operandRegex.exists(_.findFirstIn(labelValue).isDefined)
        case "!=~" => !operandRegex.exists(_.findFirstIn(labelValue).isDefined)
      }
    } else true
  }

  private def matchesPositive(labels: Map[String, String]): Boolean = {
    labels.exists { case (name, value) =>
      matchesName(name) && matchesValue(value)
    }
  }

  private def matchesNegative(labels: Map[String, String]): Boolean = {
    labels.forall { case (name, value) =>
      !(matchesName(name) && matchesValue(value))
    }
  }

  def matches(labels: Map[String, String]): Boolean = if (positive)
    matchesPositive(labels)
  else
    matchesNegative(labels)
}

object SMGFilterLabels {
  def parse(in: String): Seq[SMGFilterLabels] = {
    val s = in.strip()
    if (s.isEmpty)
      Seq()
    else
      s.split("\\s+").map { word =>
        var rem = word
        var positive = true
        rem = if (rem.startsWith("!")) {
          positive = false
          rem.drop(1)
        } else rem
        if (rem.contains("=")) {
          val arr = rem.split("=", 2)
          var name = arr(0)
          var value = arr.lift(1)
          val op = if (name.endsWith("!") && (value.getOrElse("").startsWith("~"))){
            name = name.dropRight(1)
            value = Some(value.get.drop(1))
            "!=~"
          } else if (name.endsWith("!")) {
            name = name.dropRight(1)
            "!="
          } else if (value.getOrElse("").startsWith("~")) {
            value = Some(value.get.drop(1))
            "=~"
          } else "="
          SMGFilterLabels(name, Some(op), value, positive)
        } else SMGFilterLabels(rem, None, None, positive)
      }
  }
}
