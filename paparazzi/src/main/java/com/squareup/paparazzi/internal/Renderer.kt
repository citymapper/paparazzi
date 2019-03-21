/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.squareup.paparazzi.internal

import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.ide.common.resources.deprecated.FrameworkResources
import com.android.ide.common.resources.deprecated.ResourceItem
import com.android.ide.common.resources.deprecated.ResourceRepository
import com.android.io.FolderWrapper
import com.android.layoutlib.bridge.Bridge
import com.android.layoutlib.bridge.android.RenderParamsFlags
import com.android.layoutlib.bridge.impl.DelegateManager
import com.android.tools.layoutlib.java.System_Delegate
import com.squareup.paparazzi.Environment
import com.squareup.paparazzi.PaparazziLogger
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** View rendering. */
class Renderer(
  private val environment: Environment,
  private val layoutLibCallback: PaparazziLayoutLibCallback,
  private val logger: PaparazziLogger
) : Closeable {
  private var bridge: Bridge? = null
  private var frameworkRepo: FrameworkResources? = null
  private var projectResources: ResourceRepository? = null

  /**
   * Returns a pre-configured [SessionParamsBuilder] for target API 22, Normal rendering
   * mode, AppTheme as theme and Nexus 5.
   */
  val sessionParamsBuilder: SessionParamsBuilder
    get() = SessionParamsBuilder()
        .setLogger(logger)
        .setFrameworkResources(frameworkRepo!!)
        .setDeviceConfig(DeviceConfig.NEXUS_5)
        .setProjectResources(projectResources!!)
        .setTheme("AppTheme", true)
        .setRenderingMode(RenderingMode.NORMAL)
        .setTargetSdk(22)
        .setFlag(RenderParamsFlags.FLAG_DO_NOT_RENDER_ON_CREATE, true)
        .setAssetRepository(
            PaparazziAssetRepository(environment.testResDir + "/" + environment.assetsDir)
        )

  /** Initialize the bridge and the resource maps. */
  fun prepare() {
    val dataDir = File(environment.platformDir, "data")
    val res = File(dataDir, "res")
    frameworkRepo = FrameworkResources(FolderWrapper(res)).apply {
      loadResources()
      loadPublicResources(logger)
    }

    projectResources = object : ResourceRepository(FolderWrapper(environment.resDir), false) {
      override fun createResourceItem(name: String): ResourceItem {
        return ResourceItem(name)
      }
    }
    projectResources!!.loadResources()

    val fontLocation = File(dataDir, "fonts")
    val buildProp = File(environment.platformDir, "build.prop")
    val attrs = File(res, "values" + File.separator + "attrs.xml")
    bridge = Bridge().apply {
      init(
          DeviceConfig.loadProperties(buildProp),
          fontLocation,
          DeviceConfig.getEnumMap(attrs),
          logger
      )
    }
    Bridge.getLock()
        .lock()
    try {
      Bridge.setLog(logger)
    } finally {
      Bridge.getLock()
          .unlock()
    }
  }

  override fun close() {
    frameworkRepo = null
    projectResources = null
    bridge = null

    Gc.gc()

    println("Objects still linked from the DelegateManager:")
    DelegateManager.dump(System.out)
  }

  fun render(
    bridge: com.android.ide.common.rendering.api.Bridge,
    params: SessionParams,
    frameTimeNanos: Long
  ): RenderResult {
    // TODO: Set up action bar handler properly to test menu rendering.
    // Create session params.
    System_Delegate.setBootTimeNanos(TimeUnit.MILLISECONDS.toNanos(871732800000L))
    System_Delegate.setNanosTime(TimeUnit.MILLISECONDS.toNanos(871732800000L))
    val session = bridge.createSession(params)

    try {
      if (frameTimeNanos != -1L) {
        session.setElapsedFrameTimeNanos(frameTimeNanos)
      }

      if (!session.result.isSuccess) {
        logger.error(session.result.exception, session.result.errorMessage)
      } else {
        // Render the session with a timeout of 50s.
        val renderResult = session.render(50000)
        if (!renderResult.isSuccess) {
          logger.error(session.result.exception, session.result.errorMessage)
        }
      }

      return session.toResult()
    } finally {
      session.dispose()
    }
  }

  /** Compares the golden image with the passed image. */
  fun verify(
    goldenImageName: String,
    image: BufferedImage
  ) {
    try {
      val goldenImagePath = environment.appTestDir + "/golden/" + goldenImageName
      ImageUtils.requireSimilar(goldenImagePath, image)
    } catch (e: IOException) {
      logger.error(e, e.message)
    }
  }

  /**
   * Create a new rendering session and test that rendering the given layout doesn't throw any
   * exceptions and matches the provided image.
   *
   * If frameTimeNanos is >= 0 a frame will be executed during the rendering. The time indicates
   * how far in the future is.
   */
  @JvmOverloads
  fun renderAndVerify(
    params: SessionParams,
    goldenFileName: String,
    frameTimeNanos: Long = -1
  ): RenderResult {
    val result = render(bridge!!, params, frameTimeNanos)
    verify(goldenFileName, result.image)
    return result
  }

  fun createParserFromPath(layoutPath: String): LayoutPullParser =
    LayoutPullParser.createFromPath("${environment.resDir}/layout/$layoutPath")

  /**
   * Create a new rendering session and test that rendering the given layout on given device
   * doesn't throw any exceptions and matches the provided image.
   */
  @JvmOverloads
  fun renderAndVerify(
    layoutFileName: String,
    goldenFileName: String,
    deviceConfig: DeviceConfig = DeviceConfig.NEXUS_5
  ): RenderResult {
    val params = createSessionParams(layoutFileName, deviceConfig)
    return renderAndVerify(params, goldenFileName)
  }

  fun createSessionParams(
    layoutFileName: String,
    deviceConfig: DeviceConfig
  ): SessionParams {
    // Create the layout pull parser.
    val parser = createParserFromPath(layoutFileName)
    // TODO: Set up action bar handler properly to test menu rendering.
    // Create session params.
    return sessionParamsBuilder
        .setParser(parser)
        .setDeviceConfig(deviceConfig)
        .setCallback(layoutLibCallback)
        .build()
  }
}