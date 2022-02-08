import com.smule.smg.core.SMGLogger
import com.smule.smgplugins.syslog.config
import com.smule.smgplugins.syslog.config.GrokLookupConf
import com.smule.smgplugins.syslog.parser.GrokParser
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGSyslogSpec extends Specification {

  private val log = SMGLogger

  private val schema1 = config.LineSchema(
    serverId = "test",
    grokPatternsFile = "smgconf/syslog-patterns/rsyslog-default",
    grokLinePatterns = Seq("SYSLOG_LINE"),
    tsLookupConf = GrokLookupConf(grokNames = Seq("syslog_ts"), jsonPath = Seq()),
    tsFormat = "MMM dd HH:mm:ss",
    tsFormatNeedsYear = true,
    tsTimeZone = None,
    idLookupConfs = List(
      GrokLookupConf(grokNames = Seq("host"), jsonPath = Seq()),
      GrokLookupConf(grokNames = Seq("syslog_tag"), jsonPath = Seq()),
      GrokLookupConf(grokNames = Seq("syslog_pid"), jsonPath = Seq())
    ),
    idPostProcessors = Seq(),
    valueLookupConfs = List(
        GrokLookupConf(grokNames = Seq("message"), jsonPath = Seq("val1")),
        GrokLookupConf(grokNames = Seq("message"), jsonPath = Seq("val2"))
    ),
    jsonIgnoreParseErrors = false,
    logFailedToParse = true,
    syslogStatsUpdateNumLines = 10,
    maxCacheCapacity = 10000
  )

  private val rsyslogJsonLines = Seq(
    "Jan  6 11:45:53 cache-qpg1221 my_endpoint[292213]: {\"val1\": 1.0, \"val2\": 2.0}",
    "Jan  6 11:45:54 cache-bom4750 my_endpoint[292213]: {\"val1\": 1.5, \"val2\": 2.5}",
    "Jan 16 11:45:55 cache-cdg20737 my_endpoint[292213]: {\"val1\": 3, \"val2\": 4}"
  )

  "GrokParser" should {
    "work" in {
      val parser = new GrokParser(schema1, log)
      rsyslogJsonLines.foreach { ln =>
        val opt = parser.parseData(ln)
        log.info(opt.get)
      }
      1 equals 1
    }
  }
}
