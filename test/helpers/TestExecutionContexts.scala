package helpers

import com.smule.smg.core.{ExecutionContexts, IntervalThreadsConfig}

import scala.concurrent.ExecutionContext

class TestExecutionContexts extends ExecutionContexts {

  override def defaultCtx: ExecutionContext = ExecutionContext.global

  override def rrdGraphCtx: ExecutionContext = ExecutionContext.global

  override def monitorCtx: ExecutionContext = ExecutionContext.global

  override def ctxForInterval(interval: Int): ExecutionContext = ExecutionContext.global

  override def initializeUpdateContexts(intervals: Map[Int,IntervalThreadsConfig]): Unit = {}
}
