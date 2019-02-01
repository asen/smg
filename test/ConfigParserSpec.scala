import java.io.File

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLogger
import helpers.TestConfigSvc
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
  * Created by asen on 5/9/17.
  */
@RunWith(classOf[JUnitRunner])
class ConfigParserSpec extends Specification {
  val log = SMGLogger

  "SMGConfigParser.fetchCommandsTree" should {
    "work" in {
      val cp = new SMGConfigParser(log)
      val c = cp.getNewConfig(Seq(), "smgconf/config-dev.yml")
      log.info(c.humanDesc)
      // TODO verify that globals and objects are parsed correctly
      c.allErrors.size mustEqual 0
    }
  }

}
