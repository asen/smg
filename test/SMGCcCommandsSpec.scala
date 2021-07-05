import com.smule.smg.core.{CommandResultListString, ParentCommandData, SMGCmd, SMGLogger}
import com.smule.smgplugins.cc.kv.SMGKvParseCommands
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner


@RunWith(classOf[JUnitRunner])
class SMGCcCommandsSpec extends Specification {
  private val log = SMGLogger

  "SMGKvParseCommands" should {
    "work" in {
      val out = SMGCmd("cat test-data/mysql-global-status.txt").run()
      val pdata = ParentCommandData(CommandResultListString(out, None), None)
      val c = new SMGKvParseCommands(log)
      val parsed = c.kvParseCommand("kv", "parse -d \\s+", 30, Some(pdata))
      log.info(s"PARSED: $parsed")
      val ppdata = ParentCommandData(parsed, None)
      var res = c.kvParseCommand("kv",
        "get questions",
        30, Some(ppdata))
      log.info(s"RES: $res")
      1.equals(1)
    }
  }
}
