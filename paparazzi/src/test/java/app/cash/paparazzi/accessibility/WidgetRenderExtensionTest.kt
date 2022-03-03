package app.cash.paparazzi.accessibility

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.RenderExtension
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import app.cash.paparazzi.internal.ImageUtils
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class WidgetRenderExtensionTest {
  @get:Rule
  val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.NEXUS_5.copy(softButtons = false),
    snapshotHandler = TestSnapshotVerifier(),
    renderExtensions = setOf(WidgetRenderExtension()),
    minThumbnailSize = 0
  )

  @Test
  fun test() {
    val view = TextView(paparazzi.context).apply {
      layoutParams = ViewGroup.LayoutParams(
        250,
        250
      )
      gravity = Gravity.CENTER
      text = "Sample Text"
      setBackgroundColor(Color.RED)
    }
    paparazzi.snapshot(view)
  }

  class WidgetRenderExtension : RenderExtension {
    private val location = IntArray(2)

    override fun renderView(contentView: View): View = contentView
    override fun renderImage(contentView: View, generatedImage: BufferedImage): BufferedImage {
      contentView.getLocationInWindow(location)
      return generatedImage.getSubimage(location[0], location[1], contentView.measuredWidth, contentView.measuredHeight)
    }
  }

  private class TestSnapshotVerifier : SnapshotHandler {
    override fun newFrameHandler(
      snapshot: Snapshot,
      frameCount: Int,
      fps: Int
    ): SnapshotHandler.FrameHandler {
      return object : SnapshotHandler.FrameHandler {
        override fun handle(image: BufferedImage) {
          val expected = File("src/test/resources/widget.png")
          ImageUtils.assertImageSimilar(
            relativePath = expected.path,
            image = image,
            goldenImage = ImageIO.read(expected),
            maxPercentDifferent = 0.1,
          )
        }

        override fun close() = Unit
      }
    }

    override fun close() = Unit
  }
}