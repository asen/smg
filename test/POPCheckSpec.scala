import com.smule.smg._
import com.smule.smg.core.{SMGLogger, SMGObjectUpdate}
import com.smule.smg.monitor._
import com.smule.smg.rrd.{SMGRrd, SMGRrdUpdate}
import com.smule.smgplugins.mon.pop.{POPCheck, POPCheckThreshConf}
import helpers.TestConfigSvc
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class POPCheckSpec extends Specification {

  "POPCheckThreshConf" should {
    "parse conf correctly" in {
      val confStr = "24h-5M:lt:0.7:0.5"
      val ctc = POPCheckThreshConf(confStr)
      ctc.period shouldEqual 86400
      ctc.res shouldEqual Some(300)
      ctc.op shouldEqual "lt"
      ctc.warnThresh shouldEqual Some(0.7)
      ctc.critThresh shouldEqual Some(0.5)
    }

    "parse partial conf correctly" in {
      val confStr = "12h:lte::0.5"
      val ctc = POPCheckThreshConf(confStr)
      ctc.period shouldEqual 43200
      ctc.res shouldEqual None
      ctc.op shouldEqual "lte"
      ctc.warnThresh shouldEqual None
      ctc.critThresh shouldEqual Some(0.5)
    }

    "deal with empty conf" in {
      val confStr = ""
      val ctc = POPCheckThreshConf(confStr)
      ctc.period shouldEqual 60
      ctc.res shouldEqual None
      ctc.op shouldEqual "lt"
      ctc.warnThresh shouldEqual None
      ctc.critThresh shouldEqual None
    }
  }

  val cs = new TestConfigSvc()

  private def prepareObjectUpdate(cs: TestConfigSvc, ou: SMGObjectUpdate, testTs: Int,
                                  numUpdates: Int, fillVal: Double, lastVal:Double): Unit = {
    cs.cleanTestOut
    val rrd = new SMGRrdUpdate(ou, cs)
    var startTs = testTs - ((numUpdates - 2) * ou.interval)
    rrd.checkOrCreateRrd(Some(startTs))
    while (startTs < testTs - (ou.interval * 2)) {
      rrd.updateValues(List(fillVal, fillVal), Some(startTs))
      startTs += ou.interval
    }
    rrd.updateValues(List(lastVal, lastVal), Some(testTs - ou.interval))
    rrd.updateValues(List(lastVal, lastVal), Some(testTs))
  }

  "POPCheck" should {
    "work with warning condition" in {
      cs.synchronized {
        val ou = cs.config.updateObjectsById("test.object.1")
        val testTs = SMGRrd.tssNow
        prepareObjectUpdate(cs, ou, testTs = testTs, numUpdates = 62, fillVal = 100.0, lastVal = 60.0)
        val checkConf = "1h:lt:0.7:0.5"
        val popck = new POPCheck("pop", SMGLogger, cs)
        val ret = popck.checkValue(ou, 0, testTs, 60.0, checkConf)
        // println(ret)
        ret.state shouldEqual SMGState.WARNING
        ret.desc shouldEqual "c=60 lt p=100 * 0.7 (1h:lt:0.7:0.5)"
      }
    }

    "work with critical condition" in {
      cs.synchronized {
        val ou = cs.config.updateObjectsById("test.object.1")
        val testTs = SMGRrd.tssNow
        prepareObjectUpdate(cs, ou, testTs = testTs, numUpdates = 62, fillVal = 100.0, lastVal = 45.0)
        val checkConf = "1h:lt:0.7:0.5"
        val popck = new POPCheck("pop", SMGLogger, cs)
        val ret = popck.checkValue(ou, 0, testTs, 45.0, checkConf)
        println(ret)
        ret.state shouldEqual SMGState.CRITICAL
        ret.desc shouldEqual "c=45 lt p=100 * 0.5 (1h:lt:0.7:0.5)"
      }
    }

    "work with OK condition" in {
      cs.synchronized {
        val ou = cs.config.updateObjectsById("test.object.1")
        val testTs = SMGRrd.tssNow
        prepareObjectUpdate(cs, ou, testTs = testTs, numUpdates = 62, fillVal = 100.0, lastVal = 80.0)
        val checkConf = "1h:lt:0.7:0.5"
        val popck = new POPCheck("pop", SMGLogger, cs)
        val ret = popck.checkValue(ou, 0, testTs, 80.0, checkConf)
        println(ret)
        ret.state shouldEqual SMGState.OK
        ret.desc shouldEqual "c=80 p=100 (1h:lt:0.7:0.5)"
      }
    }

    "work with agg objects with cdef vars" in {
      cs.synchronized {
        val ou = cs.config.updateObjectsById("test.object.aggu")
        val testTs = SMGRrd.tssNow
        prepareObjectUpdate(cs, ou, testTs = testTs, numUpdates = 62, fillVal = 100.0, lastVal = 80.0)
        val checkConf = "1h:lt:0.7:0.5"
        val popck = new POPCheck("pop", SMGLogger, cs)
        val ret = popck.checkValue(ou, 0, testTs, 80.0, checkConf)
        println(ret)
        ret.state shouldEqual SMGState.OK
        ret.desc shouldEqual "c=160 p=200 (1h:lt:0.7:0.5)"
      }
    }
  }
}
