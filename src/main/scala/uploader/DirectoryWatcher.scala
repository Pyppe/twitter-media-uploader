package uploader

import java.io.{FileWriter, File}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import java.util.concurrent.ScheduledFuture
import org.apache.commons.io.FilenameUtils
import org.joda.time.DateTime

import scala.concurrent.Await
import scala.concurrent.duration._

case class FileBasedState(private val dir: File) extends LoggerSupport {

  require(dir.isDirectory, s"$dir not a directory")

  private val StateFile = new File(dir, "twitter-media-uploader.uploaded-files")

  def readUploadedFileNames(): Set[String] = StateFile.synchronized {
    try {
      using(io.Source.fromFile(StateFile))(_.getLines.filter(_.nonEmpty).toSet)
    } catch {
      case err: Throwable =>
        logger.warn(s"Cannot read uploaded files from $StateFile")
        Set.empty
    }
  }

  def markFileAsUploaded(f: File) = StateFile.synchronized {
    using(new FileWriter(StateFile, true)) { fw =>
      fw.write(f.getName + "\n")
    }
  }

}

object DirectoryWatcher extends LoggerSupport {

  private def processWatchDirectory(dir: File,
                                    fileBasedState: FileBasedState,
                                    settings: Settings) = synchronized {
    val newImageFiles: List[File] = {
      val imageFiles = dir.listFiles.filter(isImageFile)
      if (imageFiles.nonEmpty) {
        val uploadedFiles = fileBasedState.readUploadedFileNames()
        imageFiles.filterNot(f => uploadedFiles(f.getName))
      } else {
        imageFiles
      }
    }.toList

    logger.info(s"Found ${newImageFiles.size} new media files")
    newImageFiles.foreach { file =>
      try {
        val url = Await.result(Twitter.tweetImage(file, settings), 10.minutes)
        logger.info(s"Uploaded ${file.getName} to $url")
        fileBasedState.markFileAsUploaded(file)
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

  object Scheduler {

    private var scheduledTask: ScheduledFuture[_] = null

    def start(dir: File, settings: Settings) = synchronized {
      if (scheduledTask == null) {
        logger.info(
          s"""
             |Starting using settings:
             | - Directory    = ${dir}
             | - Message      = ${settings.message}
             | - Consumer key = ${settings.consumer.key}
             | - Access token = ${settings.accessToken.token}
           """.stripMargin.trim
        )
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
