import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import com.smule.smg._
import com.smule.smg.core.{SMGLogger, SMGObjectUpdate}
import com.smule.smg.monitor._
import com.smule.smg.rrd.{SMGRrd, SMGRrdUpdate}
import com.smule.smgplugins.mon.ex.{ExtendedCheck, ExtendedCheckConf}
import helpers.TestConfigSvc
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ExCheckSpec extends Specification {

  private val DATE_FORMAT = "yyyy-MM-dd hh:mm:ss"

  def ds2ts(s: String): Int = {
    val dateFormat = new SimpleDateFormat(DATE_FORMAT)
    (dateFormat.parse(s).getTime / 1000).toInt
  }

  "ExtendedCheckConf" should {
    "parse conf correctly - no time spec" in {
      val confStr = "24h:lt-0.7:lt-0.5"
      val ctc = ExtendedCheckConf(confStr)
      ctc.fetchStep shouldEqual Some(86400)
      ctc.warnCheck shouldEqual Some(("lt", 0.7))
      ctc.critCheck shouldEqual Some(("lt", 0.5))
      ctc.activeCheckPeriods.size shouldEqual 0
      ctc.isActiveAt(None) shouldEqual true
      ctc.checkWarn(0.5) shouldEqual true
      ctc.checkWarn(0.7) shouldEqual false
      ctc.checkCrit(0.4) shouldEqual true
      ctc.checkCrit(0.5) shouldEqual false
    }

    "parse conf correctly - negative thresh" in {
      val confStr = "24h:lt--0.5:lt--0.7"
      val ctc = ExtendedCheckConf(confStr)
      ctc.fetchStep shouldEqual Some(86400)
      ctc.warnCheck shouldEqual Some(("lt", -0.5))
      ctc.critCheck shouldEqual Some(("lt", -0.7))
      ctc.activeCheckPeriods.size shouldEqual 0
      ctc.isActiveAt(None) shouldEqual true
      ctc.checkWarn(-0.5) shouldEqual false
      ctc.checkWarn(-0.6) shouldEqual true
      ctc.checkCrit(-0.7) shouldEqual false
      ctc.checkCrit(-0.8) shouldEqual true
    }

    "parse conf correctly - minutes in the hour only, multiple periods" in {
      val confStr = "24h:lt-0.7:lt-0.5:00-15,45-59"
      val ctc = ExtendedCheckConf(confStr)
      ctc.fetchStep shouldEqual Some(86400)
      ctc.warnCheck shouldEqual Some(("lt", 0.7))
      ctc.critCheck shouldEqual Some(("lt", 0.5))
      ctc.activeCheckPeriods.size shouldEqual 2
      ctc.activeCheckPeriods.head.dayOfWeek shouldEqual None
      ctc.activeCheckPeriods.head.dayOfMonth shouldEqual None
      ctc.activeCheckPeriods.head.startHourMinute shouldEqual Some(None, 0)
      ctc.activeCheckPeriods.head.endHourMinute shouldEqual Some(None, 15)
      val cts1 = ds2ts("2018-10-19 03:20:00")
      ctc.isActiveAt(Some(cts1)) shouldEqual false
      val cts2 = ds2ts("2018-10-19 04:10:00")
      ctc.isActiveAt(Some(cts2)) shouldEqual true
      val cts3 = ds2ts("2018-10-19 04:50:00")
      ctc.isActiveAt(Some(cts3)) shouldEqual true
    }


    "parse conf correctly - time of day only" in {
      val confStr = "24h:lt-0.7:lt-0.5:04_00-6_00"
      val ctc = ExtendedCheckConf(confStr)
      ctc.fetchStep shouldEqual Some(86400)
      ctc.warnCheck shouldEqual Some(("lt", 0.7))
      ctc.critCheck shouldEqual Some(("lt", 0.5))
      ctc.activeCheckPeriods.size shouldEqual 1
      ctc.activeCheckPeriods.head.dayOfWeek shouldEqual None
      ctc.activeCheckPeriods.head.dayOfMonth shouldEqual None
      ctc.activeCheckPeriods.head.startHourMinute shouldEqual Some(Some(4), 0)
      ctc.activeCheckPeriods.head.endHourMinute shouldEqual Some(Some(6), 0)
      val cts1 = ds2ts("2018-10-19 03:00:00")
      ctc.isActiveAt(Some(cts1)) shouldEqual false
      val cts2 = ds2ts("2018-10-19 04:00:00")
      ctc.isActiveAt(Some(cts2)) shouldEqual true
    }

    "parse conf correctly - day of week only" in {
      val confStr = "24h:lt-0.7:lt-0.5:*sun"
      val ctc = ExtendedCheckConf(confStr)
      ctc.fetchStep shouldEqual Some(86400)
      ctc.warnCheck shouldEqual Some(("lt", 0.7))
      ctc.critCheck shouldEqual Some(("lt", 0.5))
      ctc.activeCheckPeriods.size shouldEqual 1
      ctc.activeCheckPeriods.head.dayOfWeek shouldEqual Some(Calendar.SUNDAY)
      ctc.activeCheckPeriods.head.dayOfMonth shouldEqual None
      ctc.activeCheckPeriods.head.startHourMinute shouldEqual None
      ctc.activeCheckPeriods.head.endHourMinute shouldEqual None
      val cts1 = ds2ts("2018-10-19 03:00:00") // Friday
      ctc.isActiveAt(Some(cts1)) shouldEqual false
      val cts2 = ds2ts("2018-10-21 04:00:00") // Sunday
      ctc.isActiveAt(Some(cts2)) shouldEqual true
      val cts3 = ds2ts("2018-10-22 03:00:00") // Monday
      ctc.isActiveAt(Some(cts3)) shouldEqual false
    }

    "parse conf correctly - day of week" in {
      val confStr = "24h:lt-0.7:lt-0.5:04_00-6_00*mon"
      val ctc = ExtendedCheckConf(confStr)
      ctc.fetchStep shouldEqual Some(86400)
      ctc.warnCheck shouldEqual Some(("lt", 0.7))
      ctc.critCheck shouldEqual Some(("lt", 0.5))
      ctc.activeCheckPeriods.size shouldEqual 1
      ctc.activeCheckPeriods.head.dayOfWeek shouldEqual Some(Calendar.MONDAY)
      ctc.activeCheckPeriods.head.dayOfMonth shouldEqual None
      ctc.activeCheckPeriods.head.startHourMinute shouldEqual Some(Some(4), 0)
      ctc.activeCheckPeriods.head.endHourMinute shouldEqual Some(Some(6), 0)
      val cts1 = ds2ts("2018-10-22 05:00:00") // Monday
      ctc.isActiveAt(Some(cts1)) shouldEqual true
      val cts2 = ds2ts("2018-10-21 05:00:00") // Sunday
      ctc.isActiveAt(Some(cts2)) shouldEqual false
      val cts3 = ds2ts("2018-10-22 03:00:00") // Monday
      ctc.isActiveAt(Some(cts3)) shouldEqual false
    }

    "parse conf correctly - day of month" in {
      val confStr = "24h:lt-0.7:lt-0.5:04_00-6_00*6"
      val ctc = ExtendedCheckConf(confStr)
      ctc.fetchStep shouldEqual Some(86400)
      ctc.warnCheck shouldEqual Some(("lt", 0.7))
      ctc.critCheck shouldEqual Some(("lt", 0.5))
      ctc.activeCheckPeriods.size shouldEqual 1
      ctc.activeCheckPeriods.head.dayOfWeek shouldEqual None
      ctc.activeCheckPeriods.head.dayOfMonth shouldEqual Some(6)
      ctc.activeCheckPeriods.head.startHourMinute shouldEqual Some(Some(4), 0)
      ctc.activeCheckPeriods.head.endHourMinute shouldEqual Some(Some(6), 0)
      val cts1 = ds2ts("2018-09-06 05:00:00")
      ctc.isActiveAt(Some(cts1)) shouldEqual true
      val cts2 = ds2ts("2018-09-07 05:00:00")
      ctc.isActiveAt(Some(cts2)) shouldEqual false
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

  "ExtendedCheck" should {
    "work" in {
      val exCheck = new ExtendedCheck("ex", SMGLogger, cs)
      val ou = cs.config.updateObjectsById("test.object.1")
      val testTs = SMGRrd.tssNow
      prepareObjectUpdate(cs, ou, testTs = testTs, numUpdates = 62, fillVal = 100.0, lastVal = 100.0)
      exCheck.checkValue(ou, 0, testTs, 100.0, "1h:gt-90:gt-100") shouldEqual
        SMGState(testTs, SMGState.WARNING, "c=100 gt 90.0 (1h:gt-90:gt-100)")
      exCheck.checkValue(ou, 0, testTs, 100.0, "30M:gt-100:gt-110") shouldEqual
        SMGState(testTs, SMGState.OK, "c=100 (30M:gt-100:gt-110)")
    }
  }

}
