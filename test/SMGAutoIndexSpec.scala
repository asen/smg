import com.smule.smg.config.SMGAutoIndex
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
  * Created by asen on 11/18/15.
  */

@RunWith(classOf[JUnitRunner])
class SMGAutoIndexSpec extends Specification {
  "SMGAutoIndex" should {

    "work" in {
      val objects = Seq("alocalhost", "www.example.com.conns", "www.example.com.rate",
        "xlocalhost.a", "zlocalhost.a"
      )

//      val tuples = SMGAutoIndex.getLevelLcps(objects.sorted)
//      for (t <- tuples)
//        println(t)

      println(SMGAutoIndex.getAutoIndex(objects, "", None))

      List() must have size (0)
    }

  }
}