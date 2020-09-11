import com.smule.smg.core.SMGLogger
import com.smule.smgplugins.kube.{SMGKubeClient, SMGKubeClusterAuthConf}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGKubeClientSpec extends Specification {
  private val log = SMGLogger

  "SMGKubeClient" should {
    "work" in {
//      val authConf = SMGKubeClusterAuthConf.fromTokenFileAndUrl("/etc/smg/kube-token",
//        Some("https://kubernetes.default.svc:6443"))
      val authConf = SMGKubeClusterAuthConf.fromConfFile("/etc/smg/kube-config")
      val cli = new SMGKubeClient(log, "test", authConf)
//      cli.listPods().foreach { s =>
//        log.info(s"POD: ${s}")
//      }
//      cli.topNodes()
      cli.listServices().foreach { s =>
        log.info(s"SERVICE: ${s}")
      }
      1 equals(1)
    }
  }
}
