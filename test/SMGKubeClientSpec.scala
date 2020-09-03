import com.smule.smg.core.SMGLogger
import com.smule.smgplugins.kubeConf.SMGKubeClient
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGKubeClientSpec extends Specification {
  private val log = SMGLogger

  "SMGKubeClient" should {
    "work" in {
      val cli = new SMGKubeClient()
      cli.listPods().foreach { s =>
        log.info(s"POD: ${s}")
      }
      1 equals(1)
    }
  }
}
