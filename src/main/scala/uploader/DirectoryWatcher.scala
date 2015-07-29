package uploader

import java.io.{FileWriter, File}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import java.util.concurrent.ScheduledFuture
import org.apache.commons.io.FilenameUtils
import org.joda.time.DateTime
import org.joda.time.format.{ISODateTimeFormat, DateTimeFormat}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class FileBasedState(private val dir: File) extends LoggerSupport {
  import DirectoryWatcher.DTF

  require(dir.isDirectory, s"$dir not a directory")

  private val StateFile = new File(dir, "twitter-media-uploader.state")

  def readLastCreationTime(): Option[DateTime] = StateFile.synchronized {
    Try {
      DateTime.parse(io.Source.fromFile(StateFile).getLines.toList.head, DTF)
    } match {
      case Success(time) => Some(time)
      case Failure(err) =>
        logger.warn(s"Could not find existing state from $StateFile")
        None
    }
  }

  def setLastCreationTime(time: DateTime): Unit = StateFile.synchronized {
    val fw = new FileWriter(StateFile)
    fw.write(time.toString(DTF))
    fw.close
  }
}

object DirectoryWatcher extends LoggerSupport {

  val DTF = ISODateTimeFormat.dateTime


  private def processWatchDirectory(dir: File,
                                    fileBasedState: FileBasedState,
                                    settings: Settings) = synchronized {
    val lastCreationTime = fileBasedState.readLastCreationTime()
    val isNewFile: (File => Boolean) = lastCreationTime match {
      case Some(lastTime) => (f: File) => fileCreationTime(f).isAfter(lastTime)
      case None           => Function.const(true)
    }
    val newImageFiles =
      dir.listFiles.
        filter(isImageFile).
        filter(isNewFile).
        sortBy(fileCreationTime(_).getMillis).
        toList

    logger.info(s"Found ${newImageFiles.size} new media files")
    newImageFiles.foreach { file =>
      try {
        val url = Await.result(Twitter.tweetImage(file, settings), 10.minutes)
        logger.info(s"Uploaded ${file.getName} to $url")
        fileBasedState.setLastCreationTime(fileCreationTime(file))
      } catch {
        case err: Throwable =>
          logger.error(s"Error tweeting $file")
      }
    }
  }

  private def isImageFile(f: File) = {
    val extension = FilenameUtils.getExtension(f.getName).toLowerCase
    f.isFile && (extension == "jpg" || extension == "jpeg")
  }

  private def fileCreationTime(file: File) = new DateTime(
    Files.readAttributes(file.toPath, classOf[BasicFileAttributes]).creationTime.toMillis
  )

  object Scheduler {

    private var scheduledTask: ScheduledFuture[_] = null

    def start(dir: File, settings: Settings) = synchronized {
      if (scheduledTask == null) {
        scheduledTask = schedule(30.seconds) {
          processWatchDirectory(dir, FileBasedState(dir), settings)
        }
      }
    }

    def shutdown(): Unit = synchronized {
      if (scheduledTask != null) {
        scheduledTask.cancel(true)
      }
    }
  }


}
