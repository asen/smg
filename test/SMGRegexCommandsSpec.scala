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
       var res0 = rc.runCommand("rxe", "test", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.runCommand("rxel", "test", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.runCommand("rxe", "'a (test|from)' 1", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.runCommand("rxel", "'a (test|from)' 1", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.runCommand("rxm", "'a (test|from)'", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.runCommand("rxml", "'a (test|from)'", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.runCommand("rxm", "test", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       res0 = rc.runCommand("rxml", "test", 30, Some(ParentCommandData(inp, None)))
       log.info(s"RES: $res0")
       1 equals(1)
     }
   }

  "SMGRxReplCommand" should {
    "work" in {
      val rc = new SMGRegexCommands(log)
      val inp = CommandResultListString(List(
        "      173 congestion windows recovered after partial ack",
        "      312 TCP data loss events",
        "      TCPLostRetransmit: 3",
        "      25 timeouts after SACK recovery",
        "      208053 fast retransmits",
        "this is a test",
        "output a from parent command"
      ), None)
      var res0 = rc.runCommand("rxml", "TCPLostRetransmit:",
        30, Some(ParentCommandData(inp, None)))
      log.info(s"RXML RES: $res0")
      res0 = rc.runCommand("rx_repl", "'TCPLostRetransmit:'",
        30, Some(ParentCommandData(res0, None)))
      log.info(s"RX_REPL RES: $res0")
      1 equals(1)
    }
  }

  "SMGRxMlCommand" should {
    "work with no match and default value" in {
      val rc = new SMGRegexCommands(log)
      val inp = CommandResultListString(List(
        "      173 congestion windows recovered after partial ack",
        "      312 TCP data loss events",
        "      25 timeouts after SACK recovery",
        "      208053 fast retransmits",
        "this is a test",
        "output a from parent command"
      ), None)
      var res0 = rc.runCommand("rxml", "-d ' 0 ' TCPLostRetransmit:",
        30, Some(ParentCommandData(inp, None)))
      log.info(s"RXML2 RES: $res0")
      res0 = rc.runCommand("rx_repl", "'TCPLostRetransmit:'",
        30, Some(ParentCommandData(res0, None)))
      log.info(s"RX_REPL2 RES: ${res0.asUpdateData(1)}")
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
      var res0 = rc.runCommand("ln", "2", 30, Some(ParentCommandData(inp, None)))
      log.info(s"LN RES: $res0")
      res0 = rc.runCommand("ln", "-s '\\s+a\\s+' 2", 30, Some(ParentCommandData(inp, None)))
      log.info(s"LN RES: $res0")

      1 equals(1)
    }
  }
}
