package app.cash.paparazzi

import android.content.Context
import android.util.AttributeSet
import android.view.BridgeInflater
import android.view.LayoutInflater
import android.view.View
import app.cash.paparazzi.agent.InterceptorRegistrar
import app.cash.paparazzi.internal.PaparazziLogger
import app.cash.paparazzi.internal.Renderer
import app.cash.paparazzi.internal.SessionParamsBuilder
import com.android.ide.common.rendering.api.Result
import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.rendering.api.ViewInfo
import com.android.layoutlib.bridge.Bridge.cleanupThread
import com.android.layoutlib.bridge.Bridge.prepareThread
import com.android.layoutlib.bridge.BridgeRenderSession
import com.android.layoutlib.bridge.impl.RenderAction
import com.android.layoutlib.bridge.impl.RenderSessionImpl
import org.junit.rules.ExternalResource
import java.awt.image.BufferedImage

open class RendererScope : ExternalResource() {
  internal lateinit var renderer: Renderer
  internal lateinit var sessionParamsBuilder: SessionParamsBuilder

  private val renderSessions = mutableMapOf<SessionParamsBuilder, PaparazziRenderSession>()
  private var currentRenderSession: RenderSessionImpl? = null

  internal fun setup(logger: PaparazziLogger) {
    prepareThread()
    registerDefaultMethodInterceptors(logger)
    InterceptorRegistrar.registerMethodInterceptors()
  }

  internal fun renderSession(forParams: SessionParamsBuilder, logger: PaparazziLogger) : PaparazziRenderSession {
    val existing = renderSessions[forParams]
    if (existing != null && existing.session == currentRenderSession) {
      return existing
    }

    currentRenderSession?.release()

    if (existing != null) {
      val sessionParams = existing.params
      val renderSession = existing.session
      renderSession.acquire(sessionParams.timeout)
      currentRenderSession = renderSession
      return existing
    }

    val sessionParams = forParams.build()
    val renderSession = createRenderSession(sessionParams)
    renderSession.init(sessionParams.timeout)

    // requires LayoutInflater to be created, which is a side-effect of RenderSessionImpl.init()
    if (forParams.appCompat) {
      initializeAppCompatIfPresent(logger)
    }

    val bridgeSession = createBridgeSession(renderSession, renderSession.inflate())
    val paparazziSession = PaparazziRenderSession(sessionParams, renderSession, bridgeSession)
    renderSessions[forParams] = paparazziSession
    currentRenderSession = renderSession
    return paparazziSession
  }

  val isInitialized get() = ::renderer.isInitialized

  override fun after() { close() }

  internal fun close() {
    currentRenderSession?.release()
    renderSessions.forEach { (_, value) -> value.dispose() }
    renderer.close()
    cleanupThread()
    InterceptorRegistrar.clearMethodInterceptors()
  }

}

internal class PaparazziRenderSession(
  val params: SessionParams,
  val session: RenderSessionImpl,
  val bridgeSession: BridgeRenderSession
) {

  val rootViews: List<ViewInfo> get() = bridgeSession.rootViews

  val image: BufferedImage get() = bridgeSession.image

  fun render(freshRender: Boolean): Result = session.render(freshRender)

  fun dispose() {
    bridgeSession.dispose()
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

private fun createBridgeSession(
  renderSession: RenderSessionImpl,
  result: Result
): BridgeRenderSession {
  try {
    val bridgeSessionClass = Class.forName("com.android.layoutlib.bridge.BridgeRenderSession")
    val constructor =
      bridgeSessionClass.getDeclaredConstructor(RenderSessionImpl::class.java, Result::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(renderSession, result) as BridgeRenderSession
  } catch (e: Exception) {
    throw RuntimeException(e)
  }
}

private fun initializeAppCompatIfPresent(logger: PaparazziLogger) {
  lateinit var appCompatDelegateClass: Class<*>
  try {
    // See androidx.appcompat.widget.AppCompatDrawableManager#preload()
    val appCompatDrawableManagerClass =
      Class.forName("androidx.appcompat.widget.AppCompatDrawableManager")
    val preloadMethod = appCompatDrawableManagerClass.getMethod("preload")
    preloadMethod.invoke(null)

    appCompatDelegateClass = Class.forName("androidx.appcompat.app.AppCompatDelegate")
  } catch (e: ClassNotFoundException) {
    logger.verbose("AppCompat not found on classpath")
    return
  }

  val layoutInflater =
    RenderAction.getCurrentContext().getSystemService("layout_inflater") as BridgeInflater

  // See androidx.appcompat.app.AppCompatDelegateImpl#installViewFactory()
  if (layoutInflater.factory == null) {
    layoutInflater.factory2 = PaparazziAppCompatLayoutInflater()
  } else {
    if (layoutInflater.factory2 !is PaparazziAppCompatLayoutInflater) {
      throw IllegalStateException(
        "The LayoutInflater already has a Factory installed so we can not install AppCompat's"
      )
    }
  }
}

private class PaparazziAppCompatLayoutInflater : LayoutInflater.Factory2 {
  override fun onCreateView(
    parent: View?,
    name: String,
    context: Context,
    attrs: AttributeSet
  ): View? {
    val appCompatViewInflaterClass =
      Class.forName("androidx.appcompat.app.AppCompatViewInflater")

    val createViewMethod = appCompatViewInflaterClass
      .getDeclaredMethod(
        "createView",
        View::class.java,
        String::class.java,
        Context::class.java,
        AttributeSet::class.java,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType
      )
      .apply { isAccessible = true }

    val inheritContext = true
    val readAndroidTheme = true
    val readAppTheme = true
    val wrapContext = true

    val newAppCompatViewInflaterInstance = appCompatViewInflaterClass
      .getConstructor()
      .newInstance()

    return createViewMethod.invoke(
      newAppCompatViewInflaterInstance, parent, name, context, attrs,
      inheritContext, readAndroidTheme, readAppTheme, wrapContext
    ) as View?
  }

  override fun onCreateView(
    name: String,
    context: Context,
    attrs: AttributeSet
  ): View? = onCreateView(null, name, context, attrs)
}

