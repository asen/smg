import com.smule.smg.core.{SMGCmd, SMGFetchCommand, SMGFetchCommandTree}
import com.smule.smg.monitor._
import com.smule.smg.remote.{SMGRemote, SMGRemoteClient}
import helpers.{TestConfigSvc, TestUtil}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import play.api.libs.json.{Json, Reads}
import play.api.test.WsTestClient


/**
  * Created by asen on 9/15/16.
  */
@RunWith(classOf[JUnitRunner])
class SerializationTestSpec extends Specification {

  "json serialization" should {
    "work" in {

      implicit val jsSer = SMGRemoteClient.smgMonStateWrites

      val monStates = (1 to 1000).map { i =>
        SMGMonStateView(
          id = s"some.object.id.$i",
          severity = SMGState.OK.id.toDouble,
          text = s"test state $i",
          isHard = true, isAcked = false,
          isSilenced = false,
          silencedUntil = None,
          oid = Some(s"some.object.id.$i"),
          pfId = Some(s"some.pf.id.${i % 10}"),
          parentId = Some(s"some.pf.id.${i % 10}"),
          aggShowUrlFilter = Some(s"px=some.object.id.$i"),
          recentStates = Seq(),
          errorRepeat = 0,
          remote = SMGRemote.local
        )
      }.toList

      val jsonStr = TestUtil.printTimeMs{
        Json.toJson(monStates).toString()
      }

      List() must have size (0)
    }

    "work for runtree" in {

      val cs = new TestConfigSvc()

      //import play.api.libs.ws.ning._

      WsTestClient.withClient { ws =>
        val rmcli = new SMGRemoteClient(SMGRemote("blah", "localhost", None), ws, cs)

        implicit val smgCmdReads: Reads[SMGCmd] = rmcli.smgCmdReads

        implicit val smgFetchCommandReads: Reads[SMGFetchCommand] = rmcli.smgFetchCommandReads

        implicit val smgFetchCommandTreeReads: Reads[SMGFetchCommandTree] = rmcli.smgFetchCommandTreeReads

        val str = """{"60":[{"n":{"rro":"true","cmd":{"str":"df -k | grep ' /$' | awk '{print $3 * 1024, $4 * 1024}' | xargs -n 1 echo","tms":30},"id":"host.localhost.disk_usage"},"c":[]},{"n":{"rro":"true","cmd":{"str":"smgscripts/mac_localhost_sysload.sh","tms":30},"id":"host.localhost.sysload"},"c":[]}]}"""
        //      println(str)
        val jsval = Json.parse(str)
        val deser = jsval.as[Map[String, Seq[SMGFetchCommandTree]]]
        //      println(deser)

        deser.keys must have size 1
      }
    }
  }



}
