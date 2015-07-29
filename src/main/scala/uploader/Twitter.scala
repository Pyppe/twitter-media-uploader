package uploader

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

import com.ning.http.client.AsyncHttpClientConfig
import org.apache.commons.io.FilenameUtils
import org.imgscalr.Scalr
import play.api.libs.oauth.OAuthCalculator
import play.api.libs.ws.{WSSignatureCalculator, WSResponse, WS}
import play.api.libs.ws.ning.{NingWSClient, NingAsyncHttpClientConfigBuilder}

import scala.concurrent.Future
import scala.util.Try

object Twitter extends LoggerSupport {
  import scala.concurrent.ExecutionContext.Implicits.global

  private val UploadUrl = "https://upload.twitter.com/1.1/media/upload.json"
  private val StatusUrl = "https://api.twitter.com/1.1/statuses/update.json"

  private val WS = {
    val builder = new AsyncHttpClientConfig.Builder(new NingAsyncHttpClientConfigBuilder().build)
    new NingWSClient(builder.build)
  }

  private def isOkResponse(r: WSResponse): Boolean = r.status == 200 || r.status == 201

  private def resizeImage(f: File): Option[File] = Try {
    val img = ImageIO.read(f)
    val resized = Scalr.resize(img, Scalr.Method.SPEED, 1600, 1200)
    val resizedFile = File.createTempFile(FilenameUtils.getBaseName(f.getName) + "_resized", ".jpg")
    ImageIO.write(resized, "jpg", resizedFile)
    Some(resizedFile)
  } getOrElse {
    logger.warn(s"Cannot resize $f")
    None
  }

  private def uploadFile(file: java.io.File)(implicit oauth: WSSignatureCalculator): Future[String] = {
    import java.nio.file.{Files, Paths}

    import org.apache.commons.codec.binary.Base64
    val data = Base64.encodeBase64String(Files.readAllBytes(Paths.get(file.getAbsolutePath)))
    // {"media_id":527126359358046208,"media_id_string":"527126359358046208","size":6674,"image":{"w":128,"h":121,"image_type":"image\/png"}}
    WS.url(UploadUrl).
      sign(oauth).
      post(postParams("media" -> data)).
      filter(isOkResponse).
      map(_.json).
      map(js => (js \ "media_id_string").as[String])
  }

  private def postStatus(text: String, mediaId: String)(implicit oauth: WSSignatureCalculator): Future[String] = {
    WS.
      url(StatusUrl).
      sign(oauth).
      post(postParams("status" -> text, "media_ids" -> mediaId)).
      filter(isOkResponse).
      map(_.json).
      map { js =>
        val id = (js \ "id_str").as[String]
        val user = (js \ "user" \ "screen_name").as[String]
        s"https://twitter.com/$user/status/$id"
      }
  }

  private def postParams(args: (String, Any)*): Map[String, Seq[String]] = {
    args.flatMap {
      case (key, values: TraversableOnce[_]) => Some(key -> values.map(_.toString).toSeq)
      case (key, value: Option[_])           => value.map(v => key -> Seq(v.toString))
      case (key, value)                      => Some(key -> Seq(value.toString))
    }.toMap
  }

  def tweetImage(image: File, settings: Settings) = {
    val resized = resizeImage(image)
    implicit val oauth: WSSignatureCalculator = OAuthCalculator(settings.consumer, settings.accessToken)
    for {
      mediaId <- uploadFile(resized.getOrElse(image))
      url     <- postStatus(settings.message, mediaId)
    } yield {
      Try(resized.map(_.delete))
      url
    }
  }

}
