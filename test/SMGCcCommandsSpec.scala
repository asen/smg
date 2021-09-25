import com.smule.smg.core.{CommandResultListDouble, CommandResultListString, ParentCommandData, SMGCmd, SMGLogger}
import com.smule.smg.rrd.SMGRrd
import com.smule.smgplugins.cc.kv.SMGKvParseCommands
import com.smule.smgplugins.cc.ts.SMGTsCommand
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
      val parsed = c.runCommand("kv", "parse -d \\s+", 30, Some(pdata))
      log.info(s"PARSED: $parsed")
      val ppdata = ParentCommandData(parsed, None)
      var res = c.runCommand("kv",
        "get questions",
        30, Some(ppdata))
      log.info(s"RES: $res")
      1.equals(1)
    }
  }

  "SMGTsCommands" should {
    "work with ts" in {
      val tsNow = SMGRrd.tssNow
      val tsRrd = tsNow
      val out = List(tsNow.toDouble, (tsNow + 1).toDouble, (tsNow - 1).toDouble)
      val pdata = ParentCommandData(CommandResultListDouble(out, Some(tsRrd)), Some(tsRrd))
      val c = new SMGTsCommand(log)
      var res = c.runCommand("ts", "age", 30, Some(pdata))
      log.info(s"TS_RES-age: $res")
      res = c.runCommand("ts", "ttl", 30, Some(pdata))
      log.info(s"TS_RES-ttl: $res")
      res = c.runCommand("ts", "delta", 30, Some(pdata))
      log.info(s"TS_RES-delta: $res")
      res = c.runCommand("ts", "delta_abs", 30, Some(pdata))
      log.info(s"TS_RES-delta_abs: $res")
      res = c.runCommand("ts", "delta_neg", 30, Some(pdata))
      log.info(s"TS_RES-delta_neg: $res")
      1.equals(1)
    }

    "work with ts_ms" in {
      val tsNow = System.currentTimeMillis()
      val tsRrd = (tsNow / 1000).toInt
      val out = List(tsNow.toDouble, (tsNow + 1000).toDouble, (tsNow - 1000).toDouble)
      val pdata = ParentCommandData(CommandResultListDouble(out, Some(tsRrd)), Some(tsRrd))
      val c = new SMGTsCommand(log)
      var res = c.runCommand("ts_ms", "age", 30, Some(pdata))
      log.info(s"TS_MS_RES-age: $res")
      res = c.runCommand("ts_ms", "ttl", 30, Some(pdata))
      log.info(s"TS_MS_RES-ttl: $res")
      res = c.runCommand("ts_ms", "delta", 30, Some(pdata))
      log.info(s"TS_MS_RES-delta: $res")
      res = c.runCommand("ts_ms", "delta_abs", 30, Some(pdata))
      log.info(s"TS_MS_RES-delta_abs: $res")
      res = c.runCommand("ts_ms", "delta_neg", 30, Some(pdata))
      log.info(s"TS_RES-delta_neg: $res")
      1.equals(1)
    }
  }
}
