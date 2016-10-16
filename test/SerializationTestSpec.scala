import com.smule.smg._
import helpers.TestUtil
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import play.api.libs.json.Json


/**
  * Created by asen on 9/15/16.
  */
@RunWith(classOf[JUnitRunner])
class SerializationTestSpec extends Specification {

  "json serialization" should {
    "work" in {

      implicit val jsSer = SMGRemoteClient.smgMonStateWrites

      val monStates = (1 to 1000).map { i =>
        SMGMonStateView(severity = SMGState.OK.id.toDouble,
          text = s"test state $i",
          isHard = true, isAcked = false,
          isSilenced = false,
          silencedUntil = None,
          oid = Some(s"some.object.id.$i"),
          pfId = Some(s"some.pf.id.${i % 10}"),
          aggShowUrlFilter = Some(s"px=some.object.id.$i"),
          currentStateVal = SMGState.OK,
          errorRepeat = 0,
          badSince = None,
          remote = SMGRemote.local
        )
      }.toList

      val jsonStr = TestUtil.printTimeMs{
        Json.toJson(monStates).toString()
      }
//      println(jsonStr)

      //monStates

      import akka.actor.{ ActorRef, ActorSystem }
      import akka.serialization._
      //import com.typesafe.config.ConfigFactory

      val system = ActorSystem("example")

      // Get the Serialization Extension
      val serialization = SerializationExtension(system)


      // Have something to serialize
      val original = monStates

      val bytes = TestUtil.printTimeMs {
        // Find the Serializer for it
        val serializer = serialization.findSerializerFor(original)
        // Turn it into bytes
        serializer.toBinary(original)
      }
//      println(bytes.toList)

      // Turn it back into an object
//      val back = serializer.fromBinary(bytes, manifest = None)

//      back mustEqual(original)

      List() must have size (0)
    }
  }

}
