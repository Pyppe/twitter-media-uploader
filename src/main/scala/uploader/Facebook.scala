package uploader

import java.io.File

import com.ning.http.client.multipart.{StringPart, FilePart}
import com.ning.http.client.{Response => AHCResponse, AsyncCompletionHandler, AsyncHttpClient, AsyncHttpClientConfig}
import com.ning.http.client.providers.jdk.MultipartRequestEntity
import com.typesafe.config.ConfigFactory
import play.api.libs.ws.ning.{NingWSResponse, NingWSClient, NingAsyncHttpClientConfigBuilder}
import play.api.libs.ws.WSResponse
import scala.concurrent.{Promise, Await}
import scala.concurrent.duration._

object Facebook extends LoggerSupport {
  import scala.concurrent.ExecutionContext.Implicits.global

  private val GraphUrl = "https://graph.facebook.com/v2.4"

  private val WS = {
    val builder = new AsyncHttpClientConfig.Builder(new NingAsyncHttpClientConfigBuilder().build)
    new NingWSClient(builder.build)
  }

  val (pageId, pageAccessToken, albumId) = {
    val c = ConfigFactory.parseResources("settings.conf")
    val p = c.getConfig("facebook.page")
    val id = p.getString("id")
    val token = p.getString("accessToken")
    val albumId = p.getString("albumId")
    (id, token, albumId)
  }

  def listAlbums() = {
    WS.url(s"$GraphUrl/$pageId/albums").
      withQueryString("access_token" -> pageAccessToken).
      get.map(_.json)
  }

  def createAlbum(name: String) = {
    WS.url(s"$GraphUrl/$pageId/albums").
      withQueryString("access_token" -> pageAccessToken).
      post(httpPostParams("name" -> name)).
      filter(isOkResponse).map(_.json)
  }

  def listPagePhotos() = {
    // 804633079657970_10153128339111936?fields=message,picture,full_picture

    // 804633079657970/feed?fields=message,picture,full_picture

    // 804633079657970/feed?fields=message,picture,full_picture,from,expanded_width,created_time,description

    WS.url(s"$GraphUrl/$pageId/feed").withQueryString(
      "access_token" -> pageAccessToken,
      "fields" -> "message,picture,full_picture,from,expanded_width,created_time,description"
    ).get.filter(isOkResponse).map(_.json)
  }

  def listAlbumPhotos() = {
    WS.url(s"$GraphUrl/$albumId/photos").
      withQueryString(
        "access_token" -> pageAccessToken,
        "type" -> "uploaded",
        "fields" -> "images,created_time"
      ).get.filter(isOkResponse).map(_.json)

    // <photo_id>?fields=images,created_time
  }

  // TODO: https://github.com/playframework/playframework/issues/902
  // https://developers.facebook.com/docs/graph-api/reference/v2.4/album/photos
  def uploadPhoto(img: File) = {

    val resized = Images.resize(img)

    val client = WS.underlying[AsyncHttpClient]
    val builder = client.preparePost(s"$GraphUrl/$albumId/photos")
    builder.addQueryParam("access_token", pageAccessToken)
    builder.setHeader("Content-Type", "multipart/form-data")
    builder.addBodyPart(new FilePart(img.getName, resized getOrElse img))
    builder.addBodyPart(new StringPart("no_story", "true"))

    val result = Promise[NingWSResponse]()
    client.executeRequest(builder.build, new AsyncCompletionHandler[AHCResponse]() {
      override def onCompleted(response: AHCResponse) = {
        result.success(NingWSResponse(response))
        response
      }
      override def onThrowable(t: Throwable) = {
        result.failure(t)
      }
    })
    result.future.
      filter(isOkResponse).
      map(_.json)
  }

  def main(args: Array[String]) {
    try {
      //println(pageAccessToken)
      //println(Await.result(createAlbum(), 10.seconds))
      //println(Await.result(listAlbums(), 10.seconds))

      println(Await.result(listAlbumPhotos(), 10.seconds))
      println(Await.result(listPagePhotos(), 10.seconds))

      //println(Await.result(uploadPhoto(new File("/home/pyppe/Dropbox/Camera Uploads/2015-01-05 20.36.58.jpg")), 30.seconds))
    } finally {
      WS.close()
    }
  }


}
