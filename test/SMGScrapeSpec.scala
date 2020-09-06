
import com.smule.smg.core.{SMGFileUtil, SMGLogger}
import com.smule.smgplugins.scrape.{OpenMetricsStat, SMGScrapePluginConfParser, SMGScrapeObjectGen, SMGYamlConfigGen}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGScrapeSpec extends Specification {
  private val log = SMGLogger

//  "OpenMetricsStat" should {
//    "work" in {
//      val txt = SMGFileUtil.getFileContents("test-data/metrics.txt")
//      val parsed = OpenMetricsStat.parseText(txt, log)
//      parsed.foreach { stat =>
//        log.info(s"STAT: $stat")
//      }
//      1 equals(1)
//    }
//  }
//
//  "SMGScrapeConfParser" should {
//    "work" in {
//      val parser = new SMGScrapeConfParser("scrape", "smgconf/scrape-plugin.yml", log)
//      println(parser.conf)
//      1 equals(1)
//    }
//  }

  "SMGScrapeObjectGen" should {
    "work" in {
      val parser = new SMGScrapePluginConfParser("scrape", "smgconf/scrape-plugin.yml", log)
      val txt = SMGFileUtil.getFileContents("test-data/metrics.txt")
      val parsed = OpenMetricsStat.parseText(txt, log)
      val ogen = new SMGScrapeObjectGen(parser.conf.targets.head, parsed, log)
      val res = ogen.generateSMGObjects()

//      def logList[T](px: String, lst: List[T]): Unit = lst.foreach { x =>
//        log.info(s"$px: " + x.toString)
//      }
//
//      log.info("================= preFetches ================")
//      logList("PREFETCH", res.preFetches)
//      log.info("================= rrdObjects ================")
//      logList("RRD_OBJ", res.rrdObjects)
//      log.info("================= viewObjects ================")
//      logList("VIEW_OBJ", res.viewObjects)
//      log.info("================= viewObjects ================")
//      logList("AGG_OBJ", res.aggObjects)
//      log.info("================= viewObjects ================")
//      logList("INDEX", res.indexes)

      val cgen = new SMGYamlConfigGen()
      val yamlPfsList = cgen.yamlObjToStr(cgen.preFetchesToYamlList(res.preFetches))
      log.info(s"PREFETCH_YAMLS:\n$yamlPfsList")
      log.info("============================================")
      val yamlObjsList = cgen.yamlObjToStr(cgen.rrdObjectsToYamlList(res.rrdObjects))
      log.info(s"RRD_OBJ_YAMLS:\n$yamlObjsList")
      log.info("============================================")
      val yamlIdxList = cgen.yamlObjToStr(cgen.confIndexesToYamlList(res.indexes))
      log.info(s"INDEX_YAMLS:\n$yamlIdxList")
      1 equals(1)
    }
  }
}
