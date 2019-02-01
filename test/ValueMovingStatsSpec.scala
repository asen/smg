import com.smule.smg._
import com.smule.smgplugins.mon.anom.{AnomThreshConf, ValueMovingStats}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import com.smule.smg.monitor._

import scala.math._

/**
  * Created by asen on 1/4/16.
  */
@RunWith(classOf[JUnitRunner])
class ValueMovingStatsSpec extends Specification {

//  "SpikeDetector" should {
//
//    "work with 5 min res" in {
//      val p = SpikeCheckParams(1.5, 0.1, 12, None, None, 150)
//      val data = for (i <- 1 to 288) yield if (i < 280) i.toDouble else (i * 4).toDouble
//      val ret = SpikeDetector.checkSpike(p, data)
//      println(ret)
//      ret mustNotEqual None
//    }
//
//    "work with 1 min res" in {
//      val p = SpikeCheckParams(1.7, 0.1,24, None, None, 150)
//      val data = for (i <- 1 to 250) yield if (i < 230) i.toDouble else (i * 4).toDouble
//      val ret = SpikeDetector.checkSpike(p, data)
//      println(ret)
//      ret mustNotEqual None
//    }
//  }

  // 50 low/ 15 high
  //ruby -e 'puts (50.times.map{|i| rand(10) } + 15.times.map {|i| rand(100) }).join(" ")'
  val SPIKE_SERIES_STR = "9 3 9 0 5 7 8 6 5 4 5 3 0 4 5 5 6 2 7 4 2 6 4 4 7 4 9 0 " +
    "3 0 8 8 6 7 0 8 1 9 4 4 1 5 9 3 2 9 3 3 9 6 " +
    "19 34 50 50 2 68 50 5 26 57 98 98 62 68 73"

  //ruby -e 'puts (50.times.map{|i| rand(100) } + 20.times.map {|i| rand(10) }).join(" ")'
  val DROP_SERIES_STR = "9 99 10 67 70 13 16 0 64 75 6 55 86 7 11 58 17 97 38 42 21 " +
    "57 65 22 89 45 47 4 51 41 15 55 92 78 96 25 77 70 53 48 84 90 53 16 52 51 87 13 54 " +
    "75 0 5 9 2 2 8 3 7 7 1 6 2 7 8 4 0 5 9 2 2"

  val growingDataWithSpike = for (i <- 1 to 1460) yield if (i >= 1440) (i * 4) else i

  val droppingDataWithDrop = for (i <- 1 to 1460) yield if (i < 1440) (1460 - i) * 4 else (1460 - i)

  //  val spikeData = SPIKE_SERIES_STR.split("\\s+").map(_.toDouble)
//  val dropData = DROP_SERIES_STR.split("\\s+").map(_.toDouble)

  val rnd = scala.util.Random

  val stMaxMinutes = 30
  val ltMaxMinutes = 1800
  val ltMinutesToFill = 3 * ltMaxMinutes + 3 * stMaxMinutes + 7
  val aThresh = AnomThreshConf("1.5:30m:30h")

  val normData = (1 to ltMinutesToFill).map(_ => rnd.nextInt(100)) ++ (1 to stMaxMinutes).map(_ => rnd.nextInt(100))

  val normHighData = (1 to ltMinutesToFill).map(_ => rnd.nextInt(1000000)) ++ (1 to stMaxMinutes).map(_ => rnd.nextInt(1000000))

  val almostNormData = (1 to ltMinutesToFill).map(_ => rnd.nextInt(10)) ++ Seq(rnd.nextInt(25), rnd.nextInt(25)) ++ (1 to stMaxMinutes - 2).map(_ => rnd.nextInt(10))

  val spikeData = (1 to ltMinutesToFill).map(_ => rnd.nextInt(10)) ++ (1 to stMaxMinutes).map(_ => rnd.nextInt(50))

  val spikeHighData = (1 to ltMinutesToFill).map(_ => rnd.nextInt(1000000)) ++ (1 to stMaxMinutes).map(_ => rnd.nextInt(5000000))

  val dropData = (1 to ltMinutesToFill).map(_ => rnd.nextInt(100)) ++ (1 to (2 * stMaxMinutes)).map(_ => rnd.nextInt(10))

  def smallSpikeData = (1 to ltMinutesToFill).map(_ => rnd.nextInt(10)) ++ (1 to stMaxMinutes).map(_ => rnd.nextInt(25))

  val specialCase = (1 to ltMaxMinutes).map(_ => rnd.nextInt(10)) ++ Seq(100, 100) ++ (1 to ltMaxMinutes).map(_ => rnd.nextInt(10))

  def myNumFmt(n: Double) = SMGState.numFmt(n, None)

  def testData(data: Seq[Int], debug: Boolean = false) = {
    val o = new ValueMovingStats("blah", SMGLogger)
    var spikes = 0
    var lastSpikeStr = ""
    var lastSerializedStr = ""
    var cts = SMGRrd.tssNow - (data.size * 60)
    data.foreach { v =>
      cts += 60
      o.update(60, cts, v, stMaxMinutes, ltMaxMinutes)
      if (debug) {
        println(o.serialize)
      }
      val r = aThresh.checkAlert(o, 60, stMaxMinutes, ltMaxMinutes, myNumFmt)
      if (r.isDefined){
        //          println(o.serialize)
        spikes += 1
        lastSpikeStr = r.get
      }
      lastSerializedStr = o.serialize.toString()
    }
    println(s"spikes=$spikes $lastSpikeStr $lastSerializedStr")
    spikes
  }

  "ValueMovingStats" should {
    "detect spike" in {
      testData(spikeData, debug = false) mustNotEqual 0
    }

    "detect high spike" in {
      testData(spikeHighData) mustNotEqual 0
    }

    "detect small spike" in {
      testData(smallSpikeData, debug = false) + testData(smallSpikeData) mustNotEqual 0
    }

    "detect drop" in {
      testData(dropData) mustNotEqual 0
    }

    "work with norm" in {
      testData(normData) + testData(normHighData) + testData(almostNormData)  mustEqual 0
    }

    "work with special case" in {
      testData(specialCase) < 15  mustEqual true
    }


  }

}
