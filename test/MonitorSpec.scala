import com.smule.smg._
import org.scalatest.mockito.MockitoSugar
import play.api.inject.ApplicationLifecycle
import play.api.test._
import play.api.libs.json.Json
import org.mockito.Mockito._
import org.mockito.Matchers._
import helpers._
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner



/**
  * Created by asen on 9/3/16.
  */

@RunWith(classOf[JUnitRunner])
class MonitorSpec extends PlaySpecification with MockitoSugar {

  //  val app: Application = FakeApplication()
  //val app =  new GuiceApplicationBuilder().build()

  //  implicit lazy val app: FakeApplication = FakeApplication()

  val cs = new TestConfigSvc()

  val smg = mock[GrapherApi]
  val remotes = mock[SMGRemotesApi]

  val appLifecycle = mock[ApplicationLifecycle]

  "SMGMonitor.receiveObjMsg" should {
    "work and not send messages and logs on OK state" in {

      val startOfTest = SMGRrd.tssNow
      cs.cleanTestOut
      val notifSvc = mock[SMGMonNotifyApi]
      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
      val monlog = mock[SMGMonitorLogApi]
      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)

      // step 0, send some OK data
      val nonPfObj = cs.config.updateObjectsById("test.object.1")

      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))

      verify(notifSvc, times(0)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]](), any[Boolean]())
      verify(monlog, times(0)).logMsg(any[SMGMonitorLogMsg]())

      val ov = cs.config.viewObjectsById("test.object.1")
      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
      ms.size shouldEqual 2
      ms.head.isOk shouldEqual true
      ms.head.recentStates.head.ts shouldEqual startOfTest + 120
      ms.head.recentStates.head.desc shouldEqual "OK: value=1.5 : ( 3 / 5 )"
      ms(1).isOk shouldEqual true
      ms(1).recentStates.head.ts shouldEqual startOfTest + 120
      ms(1).recentStates.head.desc shouldEqual "OK: value=2.5"
    }

    "send a single alert on hard error and proper number of mon log msgs" in {
      val startOfTest = SMGRrd.tssNow
      cs.cleanTestOut
      val notifSvc = mock[SMGMonNotifyApi]
      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
      val monlog = mock[SMGMonitorLogApi]
      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)

      // step 0, send some OK data
      val nonPfObj = cs.config.updateObjectsById("test.object.1")

      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))

      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 180, nonPfObj, List(6.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 240, nonPfObj, List(4.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 300, nonPfObj, List(6.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 360, nonPfObj, List(6.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 420, nonPfObj, List(6.0, 1.0), 0, List()))

      verify(notifSvc, times(1)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]](), any[Boolean]())
      verify(monlog, times(3)).logMsg(any[SMGMonitorLogMsg]())

      val ov = cs.config.viewObjectsById("test.object.1")
      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
      ms.size shouldEqual 2
      ms.head.isOk shouldEqual false
      ms.head.recentStates.head.ts shouldEqual startOfTest + 420
      ms.head.recentStates.head.desc shouldEqual "CRIT: 6 >= 5 : ( 3 / 5 )"
      ms(1).isOk shouldEqual true
      ms(1).recentStates.head.ts shouldEqual startOfTest + 420
      ms(1).recentStates.head.desc shouldEqual "OK: value=1"

    }

    "send two alerts on hard error with a state change" in {
      val startOfTest = SMGRrd.tssNow
      cs.cleanTestOut
      val notifSvc = mock[SMGMonNotifyApi]
      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
      val monlog = mock[SMGMonitorLogApi]
      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)

      // step 0, send some OK data
      val nonPfObj = cs.config.updateObjectsById("test.object.1")

      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))

      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 180, nonPfObj, List(6.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 240, nonPfObj, List(4.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 300, nonPfObj, List(4.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 360, nonPfObj, List(4.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 420, nonPfObj, List(6.0, 1.0), 0, List()))
      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 480, nonPfObj, List(6.0, 1.0), 0, List()))

      verify(notifSvc, times(2)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]](), any[Boolean]())
      verify(monlog, times(4)).logMsg(any[SMGMonitorLogMsg]())

      val ov = cs.config.viewObjectsById("test.object.1")
      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
      ms.size shouldEqual 2
      ms.head.isOk shouldEqual false
      ms.head.recentStates.head.ts shouldEqual startOfTest + 480
      ms.head.recentStates.head.desc shouldEqual "CRIT: 6 >= 5 : ( 3 / 5 )"
      ms(1).isOk shouldEqual true
      ms(1).recentStates.head.ts shouldEqual startOfTest + 480
      ms(1).recentStates.head.desc shouldEqual "OK: value=1"

    }

//    "send two alerts and recovery on hard error with a state change and recovery" in {
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      // step 0, send some OK data
//      val nonPfObj = cs.config.updateObjectsById("test.object.1")
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 180, nonPfObj, List(6.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 240, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 300, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 360, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 420, nonPfObj, List(6.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 480, nonPfObj, List(1.5, 1.0), 0, List()))
//
//      verify(notifSvc, times(2)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(notifSvc, times(3)).sendRecoveryMessages(any[SMGMonState]()) // sent 2 times - for each var
//      verify(monlog, times(5)).logMsg(any[SMGMonitorLogMsg]())
//
//      val ov = cs.config.viewObjectsById("test.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual true
//      ms.head.recentStates.head.ts shouldEqual startOfTest + 480
//      ms.head.recentStates.head.desc shouldEqual "OK: value=1.5 : ( 3.0 / 5.0 )"
//      ms(1).isOk shouldEqual true
//      ms(1).recentStates.head.ts shouldEqual startOfTest + 480
//      ms(1).recentStates.head.desc shouldEqual "OK: value=1.0"
//
//    }
//
//    "send no alerts and recovery on acknowledged hard error with a state change and recovery" in {
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      // step 0, send some OK data
//      val nonPfObj = cs.config.updateObjectsById("test.object.1")
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 180, nonPfObj, List(6.0, 1.0), 0, List()))
//      mon.silenceLocalObject(nonPfObj.id, SMGMonSilenceAction(SMGMonSilenceAction.ACK, true, None))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 240, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 300, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 360, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 420, nonPfObj, List(6.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 480, nonPfObj, List(1.5, 1.0), 0, List()))
//
//      verify(notifSvc, times(0)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(notifSvc, times(3)).sendRecoveryMessages(any[SMGMonState]()) // sent 2 times - for each var
//      verify(monlog, times(5)).logMsg(any[SMGMonitorLogMsg]())
//
//      val ov = cs.config.viewObjectsById("test.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual true
//      ms.head.recentStates.head.ts shouldEqual startOfTest + 480
//      ms.head.recentStates.head.desc shouldEqual "OK: value=1.5 : ( 3.0 / 5.0 )"
//      ms(1).isOk shouldEqual true
//      ms(1).recentStates.head.ts shouldEqual startOfTest + 480
//      ms(1).recentStates.head.desc shouldEqual "OK: value=1.0"
//
//    }
//
//    "send no alerts and recovery on silenced hard error with a state change and recovery" in {
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      // step 0, send some OK data
//      val nonPfObj = cs.config.updateObjectsById("test.object.1")
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 180, nonPfObj, List(6.0, 1.0), 0, List()))
//      mon.silenceLocalObject(nonPfObj.id, SMGMonSilenceAction(SMGMonSilenceAction.SILENCE, true, Some(startOfTest + 3600)))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 240, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 300, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 360, nonPfObj, List(4.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 420, nonPfObj, List(6.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 480, nonPfObj, List(1.5, 1.0), 0, List()))
//
//      verify(notifSvc, times(0)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(notifSvc, times(3)).sendRecoveryMessages(any[SMGMonState]()) // sent 2 times - for each var
//      verify(monlog, times(5)).logMsg(any[SMGMonitorLogMsg]())
//
//      val ov = cs.config.viewObjectsById("test.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual true
//      ms.head.recentStates.head.ts shouldEqual startOfTest + 480
//      ms.head.recentStates.head.desc shouldEqual "OK: value=1.5 : ( 3.0 / 5.0 )"
//      ms(1).isOk shouldEqual true
//      ms(1).recentStates.head.ts shouldEqual startOfTest + 480
//      ms(1).recentStates.head.desc shouldEqual "OK: value=1.0"
//
//    }
//
//
//    "work with fetch errors" in {
//
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      // step 0, send some OK data
//      val nonPfObj = cs.config.updateObjectsById("test.object.1")
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 180, nonPfObj, List(), 7, List("fetch error")))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 240, nonPfObj, List(), 7, List("fetch error")))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 300, nonPfObj, List(), 7, List("fetch error")))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 360, nonPfObj, List(), 7, List("fetch error")))
//
//
//      verify(notifSvc, times(1)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(monlog, times(3)).logMsg(any[SMGMonitorLogMsg]())
//
//      val ov = cs.config.viewObjectsById("test.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual false
//      ms.head.recentStates.head.ts shouldEqual startOfTest + 360
//      ms.head.recentStates.head.desc shouldEqual "Fetch error: exit=7, OUTPUT: fetch error"
//      ms(1).isOk shouldEqual false
//      ms(1).recentStates.head.ts shouldEqual startOfTest + 360
//      ms(1).recentStates.head.desc shouldEqual "Fetch error: exit=7, OUTPUT: fetch error"
//    }
//
//    "work with mix of fetch errors and var errors" in {
//
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      // step 0, send some OK data
//      val nonPfObj = cs.config.updateObjectsById("test.object.1")
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 180, nonPfObj, List(), 7, List("fetch error")))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 300, nonPfObj, List(), 7, List("fetch error")))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 360, nonPfObj, List(6.0, 1.0), 0, List()))
//
//      verify(notifSvc, times(1)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(monlog, times(4)).logMsg(any[SMGMonitorLogMsg]()) // 3 errors + 1 recovery
//
//      val ov = cs.config.viewObjectsById("test.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual false
//      ms.head.recentStates.head.ts shouldEqual startOfTest + 360
//      ms.head.recentStates.head.desc shouldEqual "CRIT: 6.0 >= 5.0 : ( 3.0 / 5.0 )"
//      ms(1).isOk shouldEqual true
//      ms(1).recentStates.head.ts shouldEqual startOfTest + 360
//      ms(1).recentStates.head.desc shouldEqual "OK: value=1.0"
//    }
//
//
//    "work with mix of var errors and fetch errors" in {
//
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      // step 0, send some OK data
//      val nonPfObj = cs.config.updateObjectsById("test.object.1")
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest, nonPfObj, List(1.0, 2.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, nonPfObj, List(2.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, nonPfObj, List(1.5, 2.5), 0, List()))
//
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 180, nonPfObj, List(6.0, 1.0), 0, List()))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 240, nonPfObj, List(), 7, List("fetch error")))
//      mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 300, nonPfObj, List(), 7, List("fetch error")))
//
//      verify(notifSvc, times(1)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(monlog, times(3)).logMsg(any[SMGMonitorLogMsg]()) // 3 errors
//
//      val ov = cs.config.viewObjectsById("test.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual false
//      ms.head.recentStates.head.ts shouldEqual startOfTest + 300
//      ms.head.recentStates.head.desc shouldEqual "Fetch error: exit=7, OUTPUT: fetch error"
//      ms(1).isOk shouldEqual false
//      ms(1).recentStates.head.ts shouldEqual startOfTest + 300
//      ms(1).recentStates.head.desc shouldEqual "Fetch error: exit=7, OUTPUT: fetch error"
//    }

  } //receiveObjMsg


  "SMGMonitor.processPfMsg" should {
    "work and not send messages and logs on OK states" in {

      val startOfTest = SMGRrd.tssNow
      cs.cleanTestOut
      val notifSvc = mock[SMGMonNotifyApi]
      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
      val monlog = mock[SMGMonitorLogApi]
      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)

      val pf = cs.config.preFetches("test.prefetch")
      val pfObj1 = cs.config.updateObjectsById("test.pf.object.1")
      val pfObj2 = cs.config.updateObjectsById("test.pf.object.2")
      val pfObj3 = cs.config.updateObjectsById("test.pf.object.3")
      val pfObjs = Seq(pfObj1, pfObj2, pfObj3)

      // step 0, send some OK data

      mon.receivePfMsg(SMGDFPfMsg(startOfTest, pf.id, pfObj1.interval, pfObjs, 0, List(), None))
      pfObjs.foreach { pfo =>
        mon.receiveObjMsg(SMGDFObjMsg(startOfTest, pfo, List(1.0, 2.0), 0, List()))
      }

      mon.receivePfMsg(SMGDFPfMsg(startOfTest + 60, pf.id, pfObj1.interval, pfObjs, 0, List(), None))
      pfObjs.foreach { pfo =>
        mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 60, pfo, List(2.0, 1.0), 0, List()))
      }

      mon.receivePfMsg(SMGDFPfMsg(startOfTest + 120, pf.id, pfObj1.interval, pfObjs, 0, List(), None))
      pfObjs.foreach { pfo =>
        mon.receiveObjMsg(SMGDFObjMsg(startOfTest + 120, pfo, List(1.0, 2.0), 0, List()))
      }


      verify(notifSvc, times(0)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]](), any[Boolean]())
      verify(monlog, times(0)).logMsg(any[SMGMonitorLogMsg]())

      val ov = cs.config.viewObjectsById("test.pf.object.1")
      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
      ms.size shouldEqual 2
      ms.head.isOk shouldEqual true
      ms.head.recentStates.head.ts shouldEqual startOfTest + 120
      ms.head.recentStates.head.desc shouldEqual "OK: value=1 : ( 3 / 5 )"
      ms(1).isOk shouldEqual true
      ms(1).recentStates.head.ts shouldEqual startOfTest + 120
      ms(1).recentStates.head.desc shouldEqual "OK: value=2"
    }

//    "work and send messages and logs on pf error states" in {
//
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      val pf = cs.config.preFetches("test.prefetch")
//      val pfObj1 = cs.config.updateObjectsById("test.pf.object.1")
//      val pfObj2 = cs.config.updateObjectsById("test.pf.object.2")
//      val pfObj3 = cs.config.updateObjectsById("test.pf.object.3")
//      val pfObjs = Seq(pfObj1, pfObj2, pfObj3)
//
//      // step 0, send some OK data
//
//      mon.receivePfMsg(SMGDFPfMsg(startOfTest, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(startOfTest, pfo, List(1.0, 2.0), 0, List()))
//      }
//
//      var curTs = startOfTest + 60
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(2.0, 1.0), 0, List()))
//      }
//
//      curTs = startOfTest + 120
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(1.0, 2.0), 0, List()))
//      }
//
//      // step 1 send error msgs
//      curTs = startOfTest + 180
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 240
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 300
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 360
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      verify(notifSvc, times(1)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(monlog, times(3)).logMsg(any[SMGMonitorLogMsg]())
//
//      val ov = cs.config.viewObjectsById("test.pf.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual false
//      ms.head.recentStates.head.ts shouldEqual curTs
//      ms.head.recentStates.head.desc shouldEqual "Fetch error: exit=7, OUTPUT: pf error"
//      ms(1).isOk shouldEqual false
//      ms(1).recentStates.head.ts shouldEqual curTs
//      ms(1).recentStates.head.desc shouldEqual "Fetch error: exit=7, OUTPUT: pf error"
//    }
//
//    "work and only send logs on pf acknowledged error states" in {
//
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      val pf = cs.config.preFetches("test.prefetch")
//      val pfObj1 = cs.config.updateObjectsById("test.pf.object.1")
//      val pfObj2 = cs.config.updateObjectsById("test.pf.object.2")
//      val pfObj3 = cs.config.updateObjectsById("test.pf.object.3")
//      val pfObjs = Seq(pfObj1, pfObj2, pfObj3)
//
//      // step 0, send some OK data
//
//      mon.receivePfMsg(SMGDFPfMsg(startOfTest, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(startOfTest, pfo, List(1.0, 2.0), 0, List()))
//      }
//
//      var curTs = startOfTest + 60
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(2.0, 1.0), 0, List()))
//      }
//
//      curTs = startOfTest + 120
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(1.0, 2.0), 0, List()))
//      }
//
//      // step 1 send error msgs
//      curTs = startOfTest + 180
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      //ack the pf error
//      mon.silenceLocalObject(pf.id, SMGMonSilenceAction(SMGMonSilenceAction.ACK_PF, true, None))
//
//
//      curTs = startOfTest + 240
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 300
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 360
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      verify(notifSvc, times(0)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(monlog, times(3)).logMsg(any[SMGMonitorLogMsg]())
//
//      val ov = cs.config.viewObjectsById("test.pf.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual false
//      ms.head.recentStates.head.ts shouldEqual curTs
//      ms.head.recentStates.head.desc shouldEqual "Fetch error: exit=7, OUTPUT: pf error"
//      ms(1).isOk shouldEqual false
//      ms(1).recentStates.head.ts shouldEqual curTs
//      ms(1).recentStates.head.desc shouldEqual "Fetch error: exit=7, OUTPUT: pf error"
//    }
//
//    "work and send messages and logs on mix pf error and var error states" in {
//
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      val pf = cs.config.preFetches("test.prefetch")
//      val pfObj1 = cs.config.updateObjectsById("test.pf.object.1")
//      val pfObj2 = cs.config.updateObjectsById("test.pf.object.2")
//      val pfObj3 = cs.config.updateObjectsById("test.pf.object.3")
//      val pfObjs = Seq(pfObj1, pfObj2, pfObj3)
//
//      // step 0, send some OK data
//
//      mon.receivePfMsg(SMGDFPfMsg(startOfTest, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(startOfTest, pfo, List(1.0, 2.0), 0, List()))
//      }
//
//      var curTs = startOfTest + 60
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(2.0, 1.0), 0, List()))
//      }
//
//      curTs = startOfTest + 120
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(1.0, 2.0), 0, List()))
//      }
//
//      // step 1 send error msgs
//      curTs = startOfTest + 180
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 240
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 300
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 360
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 420
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(6.0, 1.0), 0, List()))
//      }
//
//      verify(notifSvc, times(2)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(monlog, times(6)).logMsg(any[SMGMonitorLogMsg]()) // 3 pf errors + 1 pf recovery + 1 var recovery + 1 var crit
//
//      val ov = cs.config.viewObjectsById("test.pf.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual false
//      ms.head.recentStates.head.ts shouldEqual curTs
//      ms.head.recentStates.head.desc shouldEqual "CRIT: 6.0 >= 5.0 : ( 3.0 / 5.0 )"
//      ms(1).isOk shouldEqual true
//      ms(1).recentStates.head.ts shouldEqual curTs
//      ms(1).recentStates.head.desc shouldEqual "OK: value=1.0"
//    }
//
//    "work and send messages and logs on flapping pf error and var error states" in {
//
//      val startOfTest = SMGRrd.tssNow
//      cs.cleanTestOut
//      val notifSvc = mock[SMGMonNotifyApi]
//      when(notifSvc.serializeState()) thenReturn Json.toJson(Map[String, String]())
//      val monlog = mock[SMGMonitorLogApi]
//      val mon = new SMGMonitor(cs, smg, remotes, monlog, notifSvc, appLifecycle)
//
//      val pf = cs.config.preFetches("test.prefetch")
//      val pfObj1 = cs.config.updateObjectsById("test.pf.object.1")
//      val pfObj2 = cs.config.updateObjectsById("test.pf.object.2")
//      val pfObj3 = cs.config.updateObjectsById("test.pf.object.3")
//      val pfObjs = Seq(pfObj1, pfObj2, pfObj3)
//
//      // step 0, send some OK data
//
//      mon.receivePfMsg(SMGDFPfMsg(startOfTest, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(startOfTest, pfo, List(1.0, 2.0), 0, List()))
//      }
//
//      var curTs = startOfTest + 60
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(2.0, 1.0), 0, List()))
//      }
//
//      curTs = startOfTest + 120
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(1.0, 2.0), 0, List()))
//      }
//
//      // step 1 send error msgs
//      curTs = startOfTest + 180
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 240
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(6.0, 1.0), 0, List()))
//      }
//
//
//      curTs = startOfTest + 300
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 7, List("pf error")))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(), 7, List("pf error")))
//      }
//
//      curTs = startOfTest + 360
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(6.0, 1.0), 0, List()))
//      }
//
//      curTs = startOfTest + 420
//      mon.receivePfMsg(SMGDFPfMsg(curTs, pf.id, pfObjs, 0, List()))
//      pfObjs.foreach { pfo =>
//        mon.receiveObjMsg(SMGDFObjMsg(curTs, pfo, List(6.0, 1.0), 0, List()))
//      }
//
//      verify(notifSvc, times(1)).sendAlertMessages(any[SMGMonState](), any[Seq[SMGMonNotifyCmd]]())
//      verify(monlog, times(8)).logMsg(any[SMGMonitorLogMsg]()) // 3 pf errors + 1 pf recovery + 1 var recovery + 1 var crit
//
//      val ov = cs.config.viewObjectsById("test.pf.object.1")
//      val ms = mon.localObjectViewsState(Seq(ov))(ov.id)
//      ms.size shouldEqual 2
//      ms.head.isOk shouldEqual false
//      ms.head.recentStates.head.ts shouldEqual curTs
//      ms.head.recentStates.head.desc shouldEqual "CRIT: 6.0 >= 5.0 : ( 3.0 / 5.0 )"
//      ms(1).isOk shouldEqual true
//      ms(1).recentStates.head.ts shouldEqual curTs
//      ms(1).recentStates.head.desc shouldEqual "OK: value=1.0"
//    }
  }
}
