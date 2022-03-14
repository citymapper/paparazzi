/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.paparazzi

import android.animation.AnimationHandler
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Handler_Delegate
import android.os.SystemClock_Delegate
import android.util.DisplayMetrics
import android.view.BridgeInflater
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import app.cash.paparazzi.internal.ImageUtils
import app.cash.paparazzi.internal.PaparazziCallback
import app.cash.paparazzi.internal.PaparazziLogger
import app.cash.paparazzi.internal.Renderer
import app.cash.paparazzi.internal.SessionParamsBuilder
import com.android.ide.common.rendering.api.Result.Status.ERROR_UNKNOWN
import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.internal.lang.System_Delegate
import com.android.layoutlib.bridge.Bridge
import com.android.layoutlib.bridge.impl.RenderAction
import com.android.layoutlib.bridge.impl.RenderSessionImpl
import java.awt.image.BufferedImage
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Date
import java.util.concurrent.TimeUnit
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class Paparazzi @JvmOverloads constructor(
  private val environment: Environment = detectEnvironment(),
  private val deviceConfig: DeviceConfig = DeviceConfig.NEXUS_5,
  private val theme: String = "android:Theme.Material.NoActionBar.Fullscreen",
  private val renderingMode: RenderingMode = RenderingMode.NORMAL,
  private val appCompatEnabled: Boolean = true,
  private val maxPercentDifference: Double = 0.1,
  private val snapshotHandler: SnapshotHandler = determineHandler(maxPercentDifference),
  private val renderExtensions: Set<RenderExtension> = setOf(),
  private val managedRendererScope: RendererScope? = null,
) : TestRule {
  private val THUMBNAIL_SIZE = 1000

  private val logger = PaparazziLogger()
  private val rendererScope = managedRendererScope ?: RendererScope()
  private val cleanupRendererScope = managedRendererScope == null
  private lateinit var sessionParamsBuilder: SessionParamsBuilder
  private lateinit var renderer: Renderer
  private lateinit var renderSession: PaparazziRenderSession
  private var testName: TestName? = null

  val layoutInflater: LayoutInflater
    get() = RenderAction.getCurrentContext().getSystemService("layout_inflater") as BridgeInflater

  val resources: Resources
    get() = RenderAction.getCurrentContext().resources

  val context: Context
    get() = RenderAction.getCurrentContext()

  private val contentRoot = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
        |              android:layout_width="match_parent"
        |              android:layout_height="match_parent"/>
        """.trimMargin()

  override fun apply(
    base: Statement,
    description: Description
  ): Statement {
    return object : Statement() {
      override fun evaluate() {
        prepare(description)
        try {
          base.evaluate()
        } finally {
          close()
        }
      }
    }
  }

  fun prepare(description: Description) {
    forcePlatformSdkVersion(environment.compileSdkVersion)

    val layoutlibCallback =
      PaparazziCallback(logger, environment.packageName, environment.resourcePackageNames)
    layoutlibCallback.initResources()

    testName = description.toTestName()

    if (!rendererScope.isInitialized) {
      // Internally managed renderer scope, or the initial run
      rendererScope.setup(logger)
      renderer = Renderer(environment, layoutlibCallback, logger, maxPercentDifference)
      sessionParamsBuilder = renderer.prepare()
      rendererScope.renderer = renderer
      rendererScope.sessionParamsBuilder = sessionParamsBuilder
    } else {
      // Subsequent runs
      renderer = rendererScope.renderer
      sessionParamsBuilder = rendererScope.sessionParamsBuilder
    }

    sessionParamsBuilder = sessionParamsBuilder
        .copy(
            layoutPullParser = SessionParamsBuilder.LayoutPullParserSpec.fromString(contentRoot),
            deviceConfig = deviceConfig,
            renderingMode = renderingMode,
            appCompat = appCompatEnabled
        )
        .withTheme(theme)

    renderSession = rendererScope.renderSession(sessionParamsBuilder, logger)
    Bitmap.setDefaultDensity(DisplayMetrics.DENSITY_DEVICE_STABLE)
  }

  fun close() {
    testName = null
    if (cleanupRendererScope) {
      rendererScope.close()
    }
    snapshotHandler.close()
  }

  fun <V : View> inflate(@LayoutRes layoutId: Int): V = layoutInflater.inflate(layoutId, null) as V

  fun snapshot(
    name: String? = null,
    deviceConfig: DeviceConfig? = null,
    theme: String? = null,
    renderingMode: RenderingMode? = null,
    composable: @Composable () -> Unit
  ) {
    val hostView = ComposeView(context)
    // During onAttachedToWindow, AbstractComposeView will attempt to resolve its parent's
    // CompositionContext, which requires first finding the "content view", then using that to
    // find a root view with a ViewTreeLifecycleOwner
    val parent = FrameLayout(context).apply { id = android.R.id.content }
    parent.addView(hostView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    PaparazziComposeOwner.register(parent)
    hostView.setContent(composable)
    snapshot(parent, name, deviceConfig, theme, renderingMode)
  }

  @JvmOverloads
  fun snapshot(
    view: View,
    name: String? = null,
    deviceConfig: DeviceConfig? = null,
    theme: String? = null,
    renderingMode: RenderingMode? = null
  ) {
    takeSnapshots(view, name, deviceConfig, theme, renderingMode, 0, -1, 1)
  }

  @JvmOverloads
  fun gif(
    view: View,
    name: String? = null,
    deviceConfig: DeviceConfig? = null,
    theme: String? = null,
    renderingMode: RenderingMode? = null,
    start: Long = 0L,
    end: Long = 500L,
    fps: Int = 30
  ) {
    // Add one to the frame count so we get the last frame. Otherwise a 1 second, 60 FPS animation
    // our 60th frame will be at time 983 ms, and we want our last frame to be 1,000 ms. This gets
    // us 61 frames for a 1 second animation, 121 frames for a 2 second animation, etc.
    val durationMillis = (end - start).toInt()
    val frameCount = (durationMillis * fps) / 1000 + 1
    val startNanos = TimeUnit.MILLISECONDS.toNanos(start)
    takeSnapshots(view, name, deviceConfig, theme, renderingMode, startNanos, fps, frameCount)
  }

  private fun takeSnapshots(
    view: View,
    name: String?,
    deviceConfig: DeviceConfig? = null,
    theme: String? = null,
    renderingMode: RenderingMode? = null,
    startNanos: Long,
    fps: Int,
    frameCount: Int
  ) {
    if (deviceConfig != null || theme != null || renderingMode != null) {
      sessionParamsBuilder = sessionParamsBuilder
          .copy(
              // Required to reset underlying parser stream
              layoutPullParser = SessionParamsBuilder.LayoutPullParserSpec.fromString(contentRoot),
              appCompat = appCompatEnabled
          )

      if (deviceConfig != null) {
        sessionParamsBuilder = sessionParamsBuilder.copy(deviceConfig = deviceConfig)
      }

      if (theme != null) {
        sessionParamsBuilder = sessionParamsBuilder.withTheme(theme)
      }

      if (renderingMode != null) {
        sessionParamsBuilder = sessionParamsBuilder.copy(renderingMode = renderingMode)
      }

      renderSession = rendererScope.renderSession(sessionParamsBuilder, logger)
      Bitmap.setDefaultDensity(DisplayMetrics.DENSITY_DEVICE_STABLE)
    }

    val snapshot = Snapshot(name, testName!!, Date())

    val frameHandler = snapshotHandler.newFrameHandler(snapshot, frameCount, fps)
    frameHandler.use {
      val viewGroup = renderSession.rootViews[0].viewObject as ViewGroup
      val modifiedView = renderExtensions.fold(view) { view, renderExtension ->
        renderExtension.renderView(view)
      }

      System_Delegate.setBootTimeNanos(0L)
      try {
        withTime(0L) {
          // Initialize the choreographer at time=0.
        }

        viewGroup.addView(modifiedView)
        for (frame in 0 until frameCount) {
          val nowNanos = (startNanos + (frame * 1_000_000_000.0 / fps)).toLong()
          withTime(nowNanos) {
            val result = renderSession.render(true)
            if (result.status == ERROR_UNKNOWN) {
              throw result.exception
            }

            val image = renderSession.image
            frameHandler.handle(scaleImage(image))
          }
        }
      } finally {
        viewGroup.removeView(modifiedView)
        AnimationHandler.sAnimatorHandler.set(null)
      }
    }
  }

  private fun withTime(
    timeNanos: Long,
    block: () -> Unit
  ) {
    val frameNanos = TIME_OFFSET_NANOS + timeNanos

    // Execute the block at the requested time.
    System_Delegate.setNanosTime(frameNanos)

    val choreographer = Choreographer.getInstance()
    val areCallbacksRunningField = choreographer::class.java.getDeclaredField("mCallbacksRunning")
    areCallbacksRunningField.isAccessible = true

    try {
      areCallbacksRunningField.setBoolean(choreographer, true)

      // https://android.googlesource.com/platform/frameworks/layoutlib/+/d58aa4703369e109b24419548f38b422d5a44738/bridge/src/com/android/layoutlib/bridge/BridgeRenderSession.java#171
      // BridgeRenderSession.executeCallbacks aggressively tears down the main Looper and BridgeContext, so we call the static delegates ourselves.
      Handler_Delegate.executeCallbacks()
      val currentTimeMs = SystemClock_Delegate.uptimeMillis()
      val choreographerCallbacks =
        RenderAction.getCurrentContext().sessionInteractiveData.choreographerCallbacks
      choreographerCallbacks.execute(currentTimeMs, Bridge.getLog())

      block()
    } catch (e: Throwable) {
      Bridge.getLog().error("broken", "Failed executing Choreographer#doFrame", e, null, null)
      throw e
    } finally {
      areCallbacksRunningField.setBoolean(choreographer, false)
    }
  }

  private fun createRenderSession(sessionParams: SessionParams): RenderSessionImpl {
    val renderSession = RenderSessionImpl(sessionParams)
    renderSession.setElapsedFrameTimeNanos(0L)
    RenderSessionImpl::class.java
        .getDeclaredField("mFirstFrameExecuted")
        .apply {
          isAccessible = true
          set(renderSession, true)
        }
    return renderSession
  }

  private fun scaleImage(image: BufferedImage): BufferedImage {
    val maxDimension = Math.max(image.width, image.height)
    val scale = THUMBNAIL_SIZE / maxDimension.toDouble()
    return ImageUtils.scale(image, scale, scale)
  }

  private fun Description.toTestName(): TestName {
    val fullQualifiedName = className
    val packageName = fullQualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
    val className = fullQualifiedName.substringAfterLast('.')
    return TestName(packageName, className, methodName)
  }

  private fun forcePlatformSdkVersion(compileSdkVersion: Int) {
    val modifiersField =
      try {
        Field::class.java.getDeclaredField("modifiers")
      } catch (e: NoSuchFieldException) {
        // Hack for Java 12+ access
        // https://stackoverflow.com/q/56039341
        // https://github.com/powermock/powermock/commit/66ce9f77215bae68b45f35481abc8b52a5d5b6ae#diff-21c1fc51058efd316026f11f34f51c5c
        try {
          val getDeclaredFields0 =
            Class::class.java.getDeclaredMethod(
                "getDeclaredFields0", Boolean::class.javaPrimitiveType
            )
          getDeclaredFields0.isAccessible = true
          val fields = getDeclaredFields0.invoke(Field::class.java, false) as Array<Field>
          fields.find { it.name == "modifiers" } ?: throw e
        } catch (ex: Exception) {
          e.addSuppressed(ex)
          throw e
        }
      }
    modifiersField.isAccessible = true

    val versionClass = try {
      Paparazzi::class.java.classLoader.loadClass("android.os.Build\$VERSION")
    } catch (e: ClassNotFoundException) {
      return
    }

    versionClass
        .getDeclaredField("SDK_INT")
        .apply {
          isAccessible = true
          modifiersField.setInt(this, modifiers and Modifier.FINAL.inv())
          setInt(null, compileSdkVersion)
        }
  }


  private class PaparazziComposeOwner private constructor() : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override fun getLifecycle(): Lifecycle = lifecycleRegistry
    override fun getSavedStateRegistry(): SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    companion object {
      fun register(view: View) {
        val owner = PaparazziComposeOwner()
        owner.savedStateRegistryController.performRestore(null)
        owner.lifecycleRegistry.currentState = Lifecycle.State.CREATED
        ViewTreeLifecycleOwner.set(view, owner)
        ViewTreeSavedStateRegistryOwner.set(view, owner)
      }
    }
  }

  companion object {
    /** The choreographer doesn't like 0 as a frame time, so start an hour later. */
    internal val TIME_OFFSET_NANOS = TimeUnit.HOURS.toNanos(1L)

    private val isVerifying: Boolean =
      System.getProperty("paparazzi.test.verify")?.toBoolean() == true

    private fun determineHandler(maxPercentDifference: Double): SnapshotHandler =
      if (isVerifying) {
        SnapshotVerifier(maxPercentDifference)
      } else {
        HtmlReportWriter()
      }
  }
}
