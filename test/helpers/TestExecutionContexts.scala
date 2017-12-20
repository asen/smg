package helpers

import com.smule.smg.ExecutionContexts

import scala.concurrent.ExecutionContext

class TestExecutionContexts extends ExecutionContexts {

  override def defaultCtx: ExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext

  override def rrdGraphCtx: ExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext

  override def monitorCtx: ExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext

  override def ctxForInterval(interval: Int): ExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext

  override def initializeUpdateContexts(intervals: Seq[Int],
                                        threadsPerIntervalMap: Map[Int, Int],
                                        defaultThreadsPerInterval: Int): Unit = {}
}
