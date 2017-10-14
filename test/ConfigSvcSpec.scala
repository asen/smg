import helpers.TestConfigSvc
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
  * Created by asen on 11/3/16.
  */

@RunWith(classOf[JUnitRunner])
class ConfigSvcSpec extends Specification {

  "SMGLocalConfig.fetchCommandsTree" should {
    "work" in {

      val cs = new TestConfigSvc()
      val fct = cs.config.getFetchCommandsTrees(60)
//      println(fct.map(_.node.id))
      fct must have size 2
      fct(0).leafNodes must have size 1
      fct(1).children must have size 3
      fct(1).leafNodes must have size 3
      fct.map(_.size).sum mustEqual 5
    }
  }
}
