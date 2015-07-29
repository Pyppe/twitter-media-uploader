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

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try

object Twitter extends LoggerSupport {
  import scala.concurrent.ExecutionContext.Implicits.global

  private val WS = {
    val builder = new AsyncHttpClientConfig.Builder(new NingAsyncHttpClientConfigBuilder().build)
    new NingWSClient(builder.build)
  }

  private def uploadFile(file: java.io.File)(implicit oauth: WSSignatureCalculator): Future[String] = {
    import java.nio.file.{Files, Paths}
    // {"media_id":527126359358046208,"media_id_string":"527126359358046208","size":6674,"image":{"w":128,"h":121,"image_type":"image\/png"}}
    WS.url("https://upload.twitter.com/1.1/media/upload.json").
      sign(oauth).
      post(httpPostParams("media" -> Images.postData(file))).
      filter(isOkResponse).
      map(_.json).
      map(js => (js \ "media_id_string").as[String])
  }

  private def postStatus(text: String, mediaId: String)(implicit oauth: WSSignatureCalculator): Future[String] = {
    WS.
      url("https://api.twitter.com/1.1/statuses/update.json").
      sign(oauth).
      post(httpPostParams("status" -> text, "media_ids" -> mediaId)).
      filter(isOkResponse).
      map(_.json).
      map { js =>
        val id = (js \ "id_str").as[String]
        val user = (js \ "user" \ "screen_name").as[String]
        s"https://twitter.com/$user/status/$id"
      }
  }

  def validateSettings(settings: Settings): Unit = {
    val future =
      WS.url("https://api.twitter.com/1.1/help/configuration.json").
        sign(OAuthCalculator(settings.consumer, settings.accessToken)).
        get.filter(isOkResponse).
        map(_.json).
        map(js => (js \ "characters_reserved_per_media").as[Int]).
        map { charsPerMedia =>
          val maxSize = 140 - charsPerMedia - 1
          require(settings.message.length < maxSize, s"message <${settings.message}> too long [max $maxSize characters]")
        }

    future.onFailure {
      case _ => WS.close()
    }

    Await.result(future, 30.seconds)
  }

  def tweetImage(image: File, settings: Settings) = {
    val resized = Images.resize(image)
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
