import java.util.concurrent.{ThreadFactory, Executors, ScheduledFuture, ScheduledExecutorService}

import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._

package object uploader {

  private def singleThreadExecutor =
    Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "Scheduler thread")
        t.setDaemon(true)
        t
      }
    })

  def schedule(interval: FiniteDuration,
               delay: FiniteDuration = 5.seconds,
               executor: ScheduledExecutorService = singleThreadExecutor)
              (block: => Unit)(implicit logger: Logger): ScheduledFuture[_] = {
    import java.util.concurrent.TimeUnit.MILLISECONDS

    val task = new Runnable() {
      override def run() = {
        logger.trace("Executing task.")
        try {
          block
        } catch {
          case e: Throwable => logger.error("Handler threw an exception", e)
        }
        logger.trace("Done.")
      }
    }

    if (interval.toMillis != 0L)
      executor.scheduleAtFixedRate(task, delay.toMillis, interval.toMillis, MILLISECONDS)
    else
      executor.schedule(task, delay.toMillis, MILLISECONDS)
  }

}
