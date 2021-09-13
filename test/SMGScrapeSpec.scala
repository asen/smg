
import com.smule.smg.core.{SMGFileUtil, SMGLogger}
import com.smule.smg.openmetrics.OpenMetricsParser
import com.smule.smgplugins.scrape.{SMGScrapeCommands, SMGScrapeObjectsGen, SMGScrapePluginConfParser, SMGYamlConfigGen}
import helpers.TestConfigSvc
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGScrapeSpec extends Specification {
  private val log = SMGLogger

  "OpenMetricsStat" should {
    "work" in {
      //val txt = SMGFileUtil.getFileContents("test-data/metrics.txt")
      val txt = "http_exceptions{method=\"GET\",path=\"/api/partner-artists/%bf'%bf\\\"\",status=\"500\",app=\"nrork\"} 1"
      val parsed = OpenMetricsParser.parseText(txt, Some(log))
      log.info("LABELS: " + parsed.head.rows.head.labels)
      val dumped = OpenMetricsParser.dumpStats(parsed)
      dumped.foreach { stat =>
        log.info(s"DUMPED: $stat")
      }
      1 equals(1)
    }
  }

//  "SMGScrapeConfParser" should {
//    "work" in {
//      val parser = new SMGScrapeConfParser("scrape", "smgconf/scrape-plugin.yml", log)
//      println(parser.conf)
//      1 equals(1)
//    }
//  }

//  "SMGScrapeCommands" should {
//    "work" in {
//      val cmd = new SMGScrapeCommands(log)
//      //
//      val res = cmd.runPluginFetchCommand("fetch https://localhost:9000", 10, None)
//      println(res)
//      1 equals(1)
//    }
//  }

//  "SMGScrapeObjectGen" should {
//    "work" in {
//      val cs = new TestConfigSvc()
//      val parser = new SMGScrapePluginConfParser("scrape", "smgconf/scrape-plugin.yml", log)
//      val txt = SMGFileUtil.getFileContents("test-data/metrics.txt")
//      val parsed = OpenMetricsParser.parseText(txt, Some(log))
//      val ogen = new SMGScrapeObjectsGen(cs, parser.conf.targets.head, parsed, log)
//      val res = ogen.generateSMGObjects()
//
//      val cgen = SMGYamlConfigGen
//      val yamlPfsList = cgen.yamlObjToStr(cgen.preFetchesToYamlList(res.preFetches))
//      log.info(s"PREFETCH_YAMLS:\n$yamlPfsList")
//      log.info("============================================")
//      val yamlObjsList = cgen.yamlObjToStr(cgen.rrdObjectsToYamlList(res.objects))
//      log.info(s"RRD_OBJ_YAMLS:\n$yamlObjsList")
//      log.info("============================================")
//      val yamlIdxList = cgen.yamlObjToStr(cgen.confIndexesToYamlList(res.indexes))
//      log.info(s"INDEX_YAMLS:\n$yamlIdxList")
//      1 equals(1)
//    }
//  }
}
