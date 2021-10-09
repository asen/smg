import com.smule.smg.core.SMGRrdAggObject
import com.smule.smg.rrd.SMGRrd
import helpers.TestConfigSvc
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SMGRrdAggObjectSpec extends Specification {
  "SMGRrdAggObject" should {
    "work with counters" in {
      val cs = new TestConfigSvc()
      val aggou3c = cs.config.updateObjectsById("test.object.aggou3c")
      val aggu1 = cs.config.updateObjectsById("test.object.aggu1").asInstanceOf[SMGRrdAggObject]

      val now = SMGRrd.tssNow
      cs.cacheValues(aggou3c, now - 65, List(10.0,100.0))
      cs.cacheValues(aggou3c, now - 5, List(70.0,640.0))
      println(aggu1.isCounter)
      println(cs.fetchAggValues(aggu1))
      println(cs.getCachedValues(aggou3c, false))
      println(cs.getCachedValues(aggou3c, true))


      aggu1.ous.head mustEqual aggou3c
    }

    "work with counters2" in {
      val cs = new TestConfigSvc()
      val aggou3c = cs.config.updateObjectsById("test.object.aggou3c")
      val aggu1 = cs.config.updateObjectsById("test.object.aggu1").asInstanceOf[SMGRrdAggObject]

      val now = SMGRrd.tssNow
      cs.cacheValues(aggou3c, now - 5, List(70.0,640.0))
      println(cs.fetchAggValues(aggu1))
      println(cs.getCachedValues(aggou3c, false))
      println(cs.getCachedValues(aggou3c, true))


      aggu1.ous.head mustEqual aggou3c
    }
  }
}
