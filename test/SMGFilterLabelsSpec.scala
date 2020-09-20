import com.smule.smg.core.{SMGFilterLabels, SMGLogger}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGFilterLabelsSpec extends Specification  {
  private val log = SMGLogger

  "SMGKubeClient" should {
    "parse" in {
      val seq = SMGFilterLabels.parse("kk=vv aa!=bb cc=~dd !ee=ff gg !hh ii!=~jj")
      seq.foreach(log.info(_))
      seq.size equals(7)
    }
    "work for k=v" in {
      val labels = Map("kk" -> "vv")
      val flt = SMGFilterLabels.parse("kk=vv").head
      flt.matches(labels) &&
        !flt.matches(Map())
    }
    "work for k!=v" in {
      val labels = Map("kk" -> "vv")
      val labels2 = Map("kk" -> "v")
      val flt = SMGFilterLabels.parse("kk!=vv").head
      !flt.matches(labels) &&
        flt.matches(labels2) &&
        !flt.matches(Map())
    }
    "work for k=~v" in {
      val labels = Map("kk" -> "vv")
      val flt = SMGFilterLabels.parse("kk=~v.").head
      flt.matches(labels) && !flt.matches(Map())
    }
    "work for k!=~v" in {
      val labels = Map("kk" -> "vv")
      val labels2 = Map("kk" -> "v")
      val flt = SMGFilterLabels.parse("kk!=~v.").head
      !flt.matches(labels) && flt.matches(labels2) && !flt.matches(Map())
    }

    "work for !k=v" in {
      val labels = Map("kk" -> "vv")
      val flt = SMGFilterLabels.parse("!kk=vv").head
      !flt.matches(labels) &&
        flt.matches(Map())
    }
    "work for !k!=v" in {
      val labels = Map("kk" -> "vv")
      val labels2 = Map("kk" -> "v")
      val flt = SMGFilterLabels.parse("!kk!=vv").head
      flt.matches(labels) &&
        !flt.matches(labels2) &&
        flt.matches(Map())
    }
    "work for !k=~v" in {
      val labels = Map("kk" -> "vv")
      val flt = SMGFilterLabels.parse("!kk=~v.").head
      !flt.matches(labels) && flt.matches(Map())
    }
    "work for !k!=~v" in {
      val labels = Map("kk" -> "vv")
      val labels2 = Map("kk" -> "v")
      val flt = SMGFilterLabels.parse("!kk!=~v.").head
      flt.matches(labels) && !flt.matches(labels2) && flt.matches(Map())
    }
  }
}
