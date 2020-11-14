import com.smule.smg.core.{CommandResultListString, ParentCommandData, SMGLogger}
import com.smule.smgplugins.cc.ln.SMGLineCommand
import com.smule.smgplugins.cc.rx.SMGRegexCommands
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGRegexCommandsSpec extends Specification {
  private val log = SMGLogger

  "SMGRegexCommands" should {
     "work" in {
       val rc = new SMGRegexCommands(log)
       val inp = CommandResultListString(List(
         "this is a test",
         "output a from parent command"
       ), None)
       var res0 = rc.rxCommand("rxe", "test", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.rxCommand("rxel", "test", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.rxCommand("rxe", "'a (test|from)' 1", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.rxCommand("rxel", "'a (test|from)' 1", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.rxCommand("rxm", "'a (test|from)'", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.rxCommand("rxml", "'a (test|from)'", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.rxCommand("rxm", "test", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.rxCommand("rxml", "test", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       1 equals(1)
     }
   }

  "SMGLineCommands" should {
    "work" in {
      val rc = new SMGLineCommand(log)
      val inp = CommandResultListString(List(
        "this is a test",
        "output a from parent command"
      ), None)
      var res0 = rc.lnCommand("ln", "2", 30, Some(ParentCommandData(inp, None)))
      log.info(s"LN RES: $res0")
      res0 = rc.lnCommand("ln", "-s '\\s+a\\s+' 2", 30, Some(ParentCommandData(inp, None)))
      log.info(s"LN RES: $res0")


      1 equals(1)
    }
  }
}
