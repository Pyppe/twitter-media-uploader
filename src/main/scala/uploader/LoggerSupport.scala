package uploader

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait LoggerSupport {
  protected implicit val logger: Logger =
    Logger(LoggerFactory.getLogger(getClass.getName.replaceAll("\\$$", "")))
}
