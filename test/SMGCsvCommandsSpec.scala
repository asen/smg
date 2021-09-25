import com.smule.smg.core.{CommandResultListString, ParentCommandData, SMGCmd, SMGLogger}
import com.smule.smgplugins.cc.csv.SMGCsvCommands
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGCsvCommandsSpec extends Specification {
  private val log = SMGLogger

  "SMGCsvCommands" should {
    "work" in {
      val out = SMGCmd("cat test-data/haproxy_stats.csv").run()
      val pdata = ParentCommandData(CommandResultListString(out, None), None)
      val c = new SMGCsvCommands(log)
      val parsed = c.runCommand("csv", "parse", 30, Some(pdata))
      log.info(s"PARSED: $parsed")
      val ppdata = ParentCommandData(parsed, None)
      var res = c.runCommand("csv",
        "get '# pxname'=snp_v2_swimln1_farm svname=a37.oak slim stot",
        30, Some(ppdata))
      log.info(s"RES: $res")
      res = c.runCommand("csv",
        "get '# pxname'=snp_v2_swimln1_farm svname=~.*END slim stot",
        30, Some(ppdata))
      log.info(s"RES: $res")
      res = c.runCommand("csv",
        "get '# pxname'=snp_v2_swimln1_farm svname=!~.*oak slim stot",
        30, Some(ppdata))
      log.info(s"RES: $res")
      1.equals(1)
    }
  }
}
