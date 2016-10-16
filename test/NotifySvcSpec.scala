import com.smule.smg.{SMGState, _}
import helpers.TestConfigSvc
import org.junit.runner.RunWith
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.specs2.runner.JUnitRunner
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.test.PlaySpecification
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Await
import scala.concurrent.duration.Duration


/**
  * Created by asen on 9/5/16.
  */

@RunWith(classOf[JUnitRunner])
class NotifySvcSpec extends PlaySpecification with MockitoSugar {

  //  val app: Application = FakeApplication()
  //val app =  new GuiceApplicationBuilder().build()

  //  implicit lazy val app: FakeApplication = FakeApplication()

  val cs = new TestConfigSvc()

  val smg = mock[GrapherApi]
  val remotes = mock[SMGRemotesApi]

  val appLifecycle = mock[ApplicationLifecycle]

  "SMGMonNotifySvc" should {
    "work with var errors" in {

      val startOfTest = SMGRrd.tssNow
      cs.cleanTestOut
      val notifSvc = new SMGMonNotifySvc(cs, defaultContext)

      //val monlog = mock[SMGMonitorLogApi]
      //val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)

      val nonPfObj = cs.config.updateObjectsById("test.object.1")

      val monVarStates = nonPfObj.vars.indices.map { ix =>
        SMGMonStateObjVar(nonPfObj.id,
          ix,
          List(nonPfObj.id), None, s"lbl$ix", s"Title $ix", isAcked = false, isSilenced = false, silencedUntil = None,
          recentStates = List(
            if (ix == 0)
              SMGState(startOfTest + 120, SMGState.E_VAL_WARN, "var warn error")
            else
              SMGState(startOfTest + 120, SMGState.OK, "ok"),
            SMGState(startOfTest + 60, SMGState.E_FETCH, "fetch error"),
            SMGState(startOfTest + 0, SMGState.E_FETCH, "fetch error")
          ),
          badSince = Some(startOfTest),
          SMGRemote.local
        )
      }

      val monVarState =  SMGMonStateObjVar(nonPfObj.id,
        0,
        List(nonPfObj.id), None, s"lbl0", s"Title 0", isAcked = false, isSilenced = false, silencedUntil = None,
        recentStates = List(
          SMGState(startOfTest + 120, SMGState.E_VAL_WARN, "var warn error"),
          SMGState(startOfTest + 60, SMGState.E_FETCH, "fetch error"),
          SMGState(startOfTest + 0, SMGState.E_FETCH, "fetch error")
        ),
        badSince = Some(startOfTest),
        SMGRemote.local
      )



      var sent = false
      var fut = notifSvc.sendAlertMessages(monVarState, Seq(SMGMonNotifyCmd("test-notify", "echo", 30)))
      Await.result(fut, Duration(5, "seconds")) mustEqual true
      fut = notifSvc.checkAndResendAlertMessages(monVarState, 3600)
      Await.result(fut, Duration(5, "seconds")) mustEqual false
      Thread.sleep(1000)
      fut = notifSvc.checkAndResendAlertMessages(monVarState, 0)
      Await.result(fut, Duration(5, "seconds")) mustEqual true
      fut = notifSvc.checkAndResendAlertMessages(monVarState, 3600)
      Await.result(fut, Duration(5, "seconds")) mustEqual false
      fut = notifSvc.sendRecoveryMessages(monVarState)
      Await.result(fut, Duration(5, "seconds")) mustEqual true
      fut = notifSvc.sendRecoveryMessages(monVarState)
      Await.result(fut, Duration(5, "seconds")) mustEqual false

    }

    "work with fetch errors" in {
      val startOfTest = SMGRrd.tssNow
      cs.cleanTestOut
      val notifSvc = new SMGMonNotifySvc(cs, defaultContext)

      //val monlog = mock[SMGMonitorLogApi]
      //val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)

      val nonPfObj = cs.config.updateObjectsById("test.object.1")

      val monVarStates = nonPfObj.vars.indices.map { ix =>
        SMGMonStateObjVar(nonPfObj.id,
          ix,
          List(nonPfObj.id), None, s"lbl$ix", s"Title $ix", isAcked = false, isSilenced = false, silencedUntil = None,
          recentStates = List(
            SMGState(startOfTest + 120, SMGState.E_FETCH, "fetch error"),
            SMGState(startOfTest + 60, SMGState.E_FETCH, "fetch error"),
            SMGState(startOfTest + 0, SMGState.E_FETCH, "fetch error")
          ),
          badSince = Some(startOfTest),
          SMGRemote.local
        )
      }

      val newAggMonState = SMGMonStateAgg(monVarStates, SMGMonStateAgg.objectsUrlFilter(Seq(nonPfObj.id)))
      var sent = false
      var fut = notifSvc.sendAlertMessages(newAggMonState, Seq(SMGMonNotifyCmd("test-notify", "echo", 30)))
      Await.result(fut, Duration(5, "seconds")) mustEqual true
      fut = notifSvc.checkAndResendAlertMessages(newAggMonState, 3600)
      Await.result(fut, Duration(5, "seconds")) mustEqual false
      Thread.sleep(1000)
      fut = notifSvc.checkAndResendAlertMessages(newAggMonState, 0)
      Await.result(fut, Duration(5, "seconds")) mustEqual true
      fut = notifSvc.checkAndResendAlertMessages(newAggMonState, 3600)
      Await.result(fut, Duration(5, "seconds")) mustEqual false
      fut = notifSvc.sendRecoveryMessages(newAggMonState)
      Await.result(fut, Duration(5, "seconds")) mustEqual true
      fut = notifSvc.sendRecoveryMessages(newAggMonState)
      Await.result(fut, Duration(5, "seconds")) mustEqual false
    }

  }
}
