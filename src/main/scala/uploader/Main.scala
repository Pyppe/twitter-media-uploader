package uploader

import java.io.File

import com.typesafe.config.ConfigFactory
import play.api.libs.oauth.{RequestToken, ConsumerKey}
import scala.concurrent.duration._

case class Settings(consumer: ConsumerKey, accessToken: RequestToken, message: String)

case class Arguments(dir: Option[File] = None)

object Main {

  val AppName = "twitter-media-uploader"

  val argumentParser = new scopt.OptionParser[Arguments](AppName) {
    head(AppName, "1.0")

    opt[File]('d', "directory") action { (dir, p) =>
      p.copy(dir = Some(dir.getCanonicalFile))
    } validate { file =>
      if (file.isDirectory)
        success
      else
        failure(s"$file is not a directory")
    } text "Watch directory"

    help("help") text "Prints this usage text"
  }

  private def parseSettings(dir: File) = {
    val c = {
      val default = ConfigFactory.parseResources("settings.conf")
      val userFile = new File(dir, "settings.conf")
      if (userFile.isFile)
        ConfigFactory.parseFile(userFile).withFallback(default)
      else
        default
    }
    val consumer = ConsumerKey(
      c.getString("twitter.consumer.key"),
      c.getString("twitter.consumer.secret")
    )
    val accessToken = RequestToken(
      c.getString("twitter.accessToken.key"),
      c.getString("twitter.accessToken.secret")
    )
    Settings(consumer, accessToken, c.getString("message"))
  }

  def main(args: Array[String]) {
    val arguments = argumentParser.parse(args, Arguments()) match {
      case Some(arguments) => arguments
      case None            => sys.exit(1)
    }
    val dir = arguments.dir.getOrElse {
      val currentDir = new File(".").getCanonicalFile
      require(currentDir.isDirectory, s"$currentDir is not a directory")
      currentDir
    }

    val settings = parseSettings(dir)
    try {
      Twitter.validateSettings(settings)
    } catch {
      case err: Throwable =>
        System.err.println(s"Error validating settings: ${err.getMessage}")
        sys.exit(0)
    }

    DirectoryWatcher.Scheduler.start(dir, settings)

    while(true) {
      Thread.sleep(30.seconds.toMillis)
    }

  }

}
