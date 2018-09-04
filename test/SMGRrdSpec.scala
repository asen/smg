import com.smule.smg.{GraphOptions, SMGRraDef, SMGRrd, SMGRrdConfig}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGRrdSpec extends Specification  {

  "SMGRraDef.getDefaultRraDef" should {

    "work with interval 60" in {
      val rras = SMGRraDef.getDefaultRraDef(60)
//      rras.defs.foreach(s => System.out.println(s))
//      System.out.println(rras)
      rras.defs must have size 12
    }

    "work with interval 300" in {
      val rras = SMGRraDef.getDefaultRraDef(300)
//      rras.defs.foreach(s => System.out.println(s))
//      System.out.println(rras)
      rras.defs must have size 10
    }

    "work with interval 3600" in {
      val rras = SMGRraDef.getDefaultRraDef(3600)
//      rras.defs.foreach(s => System.out.println(s))
//      System.out.println(rras)
      rras.defs must have size 8
    }
  }

  private val dataPointsPerImage = 1821 // width=607 * dpPerPixel=3

  "SMGRrd.getDataResolution" should {
    "work fo 1M" in {
      val rraDef = SMGRraDef.getDefaultRraDef(60)
      val res = SMGRrd.getDataResolution(60, "30h",
        GraphOptions.default, Some(rraDef), dataPointsPerImage)
      res mustEqual "1M avg (estimated)"
    }

    "work for 5M" in {
      val rraDef = SMGRraDef.getDefaultRraDef(60)
      val res = SMGRrd.getDataResolution(60, "31h",
        GraphOptions.default, Some(rraDef), dataPointsPerImage)
      res mustEqual "5M avg (estimated)"
    }

    "work with pl" in {
      val rraDef = SMGRraDef.getDefaultRraDef(60)
      val res = SMGRrd.getDataResolution(60, "282d",
        GraphOptions.withSome(pl=Some("24h")), Some(rraDef), dataPointsPerImage)
      res mustEqual "6h avg (estimated)"
    }

  }
}
