import com.smule.smg.grapher.GraphOptions
import com.smule.smg.rrd.{SMGRraDef, SMGRrd}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGRrdSpec extends Specification  {

  "SMGRraDef.getDefaultRraDef" should {

    "work with interval 15" in {
      val rras = SMGRraDef.getDefaultRraDef(15, Seq("AVERAGE"))
      rras.defs.foreach(s => System.out.println(s))
      System.out.println(rras)
      rras.defs must have size 6
    }

    "work with interval 60" in {
      val rras = SMGRraDef.getDefaultRraDef(60, Seq("AVERAGE", "MAX"))
//      rras.defs.foreach(s => System.out.println(s))
//      System.out.println(rras)
      rras.defs must have size 12
    }

    "work with interval 300" in {
      val rras = SMGRraDef.getDefaultRraDef(300, Seq("AVERAGE", "MAX"))
//      rras.defs.foreach(s => System.out.println(s))
//      System.out.println(rras)
      rras.defs must have size 10
    }

    "work with interval 3600" in {
      val rras = SMGRraDef.getDefaultRraDef(3600, Seq("AVERAGE", "MAX"))
//      rras.defs.foreach(s => System.out.println(s))
//      System.out.println(rras)
      rras.defs must have size 8
    }
  }

  private val dataPointsPerImage = 1821 // width=607 * dpPerPixel=3

  "SMGRrd.getDataResolution" should {
    "work fo 1M" in {
      val rraDef = SMGRraDef.getDefaultRraDef(60, Seq("AVERAGE", "MAX"))
      val res = SMGRrd.getDataResolution(60, "30h",
        GraphOptions.default, Some(rraDef), dataPointsPerImage)
      res mustEqual "1M avg (estimated)"
    }

    "work for 5M" in {
      val rraDef = SMGRraDef.getDefaultRraDef(60, Seq("AVERAGE", "MAX"))
      val res = SMGRrd.getDataResolution(60, "31h",
        GraphOptions.default, Some(rraDef), dataPointsPerImage)
      res mustEqual "5M avg (estimated)"
    }

    "work with pl" in {
      val rraDef = SMGRraDef.getDefaultRraDef(60, Seq("AVERAGE", "MAX"))
      val res = SMGRrd.getDataResolution(60, "282d",
        GraphOptions.withSome(pl=Some("24h")), Some(rraDef), dataPointsPerImage)
      res mustEqual "6h avg (estimated)"
    }

  }

  "SMGRrd.computeRpnValue" should {
    "work" in {
      // ($ds0 * 100) / ($ds0 + $ds1))
      val rpn = "$ds0,100.0,*,$ds0,$ds1,+,/"
      val vals = List(10.0, 190.0)
      val computed = SMGRrd.computeRpnValue(rpn, vals)
      computed shouldEqual 5.0
    }
    "work with division by zero" in {
      // ($ds0 * 100) / ($ds0 + $ds1))
      val rpn =  "$ds0,100.0,*,$ds0,$ds1,+,/"
      val vals = List(0.0, 0.0)
      val computed = SMGRrd.computeRpnValue(rpn, vals)
      computed.isNaN shouldEqual true
    }
    "work with division by zero and ADDNAN" in {
      // ($ds0 * 100) / ($ds0 + $ds1))
      val rpn =  "$ds0,100.0,*,$ds0,$ds1,+,/,0.0,ADDNAN"
      val vals = List(0.0, 0.0)
      val computed = SMGRrd.computeRpnValue(rpn, vals)
      computed shouldEqual 0.0
    }

    "work with NANs" in {
      // ($ds0 * 100) / ($ds0 + $ds1))
      val rpn = "$ds0,100.0,*,$ds0,$ds1,+,/,0.0,ADDNAN"
      val vals = List(Double.NaN, Double.NaN)
      val computed = SMGRrd.computeRpnValue(rpn, vals)
      computed shouldEqual 0.0
    }

    "work for percentage" in {
      // ($ds0 * 100) / $ds1
      val rpn = "$ds0,100.0,*,$ds1,/"
      val vals = List(10.0, 50000.0)
      val computed = SMGRrd.computeRpnValue(rpn, vals)
      computed shouldEqual 0.02
    }

    "work for mem usage" in {
      val vals = List(100.0, 300.0, 50.0, 1000.0)
      // let "in = 100 - ($avail * 100 / $totl)"
      val rpn = "100,$ds0,100.0,*,$ds3,/,-"
      val computed = SMGRrd.computeRpnValue(rpn, vals)
      computed shouldEqual 90.0
      // let "out = 100 - (($avail + $cached + $buff ) * 100 / $totl)"
      val rpn2 = "100,$ds0,$ds1,+,$ds2,+,100,*,$ds3,/,-"
      val computed2 = SMGRrd.computeRpnValue(rpn2, vals)
      computed2 shouldEqual 55.0
    }

  }
}
