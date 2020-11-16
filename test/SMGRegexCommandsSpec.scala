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
      var res0 = rc.rxCommand("rxml", "TCPLostRetransmit:",
        30, Some(ParentCommandData(inp, None)))
      log.info(s"RXML RES: $res0")
      res0 = rc.rxCommand("rx_repl", "'TCPLostRetransmit:'",
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
      var res0 = rc.rxCommand("rxml", "-d ' 0 ' TCPLostRetransmit:",
        30, Some(ParentCommandData(inp, None)))
      log.info(s"RXML2 RES: $res0")
      res0 = rc.rxCommand("rx_repl", "'TCPLostRetransmit:'",
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
      var res0 = rc.lnCommand("ln", "2", 30, Some(ParentCommandData(inp, None)))
      log.info(s"LN RES: $res0")
      res0 = rc.lnCommand("ln", "-s '\\s+a\\s+' 2", 30, Some(ParentCommandData(inp, None)))
      log.info(s"LN RES: $res0")


      1 equals(1)
    }
  }
}
