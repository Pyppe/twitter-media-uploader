package uploader

import java.io.File
import java.nio.file.{Paths, Files}
import javax.imageio.ImageIO

import org.apache.commons.io.FilenameUtils
import org.imgscalr.Scalr

import scala.util.Try

object Images extends LoggerSupport {

  def isValidImage(f: File) = {
    val extension = FilenameUtils.getExtension(f.getName).toLowerCase
    Set("jpg", "jpeg")(extension)
  }

  def postData(file: File) = {
    import org.apache.commons.codec.binary.Base64
    Base64.encodeBase64String(Files.readAllBytes(Paths.get(file.getAbsolutePath)))
  }

  def resize(f: File): Option[File] = Try {
    require(isValidImage(f), s"Invalid image: $f")
    val img = ImageIO.read(f)
    val resized = Scalr.resize(img, Scalr.Method.SPEED, 1600, 1200)
    val resizedFile = File.createTempFile(FilenameUtils.getBaseName(f.getName) + "_resized", ".jpg")
    ImageIO.write(resized, "jpg", resizedFile)
    Some(resizedFile)
  } getOrElse {
    logger.warn(s"Cannot resize $f")
    None
  }

}
