import java.util.concurrent.{ThreadFactory, Executors, ScheduledFuture, ScheduledExecutorService}

import com.ning.http.client.Response
import com.typesafe.scalalogging.Logger
import play.api.libs.ws.WSResponse

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
               delay: FiniteDuration = 15.seconds,
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

  def using[A, B <: {def close(): Unit}] (closeable: B) (f: B => A): A = {
    import scala.language.reflectiveCalls
    try { f(closeable) } finally { closeable.close() }
  }

  def httpPostParams(args: (String, Any)*): Map[String, Seq[String]] = {
    args.flatMap {
      case (key, values: TraversableOnce[_]) => Some(key -> values.map(_.toString).toSeq)
      case (key, value: Option[_])           => value.map(v => key -> Seq(v.toString))
      case (key, value)                      => Some(key -> Seq(value.toString))
    }.toMap
  }

  def isOkResponse(r: WSResponse)(implicit logger: Logger): Boolean = {
    if (r.status == 200 || r.status == 201) true else {
      val uri = r.underlying[Response].getUri.toString
      logger.warn(s"Invalid HTTP ${r.status} from $uri: ${r.body}")
      false
    }
  }

}
