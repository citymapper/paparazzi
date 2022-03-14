package app.cash.paparazzi

import app.cash.paparazzi.agent.InterceptorRegistrar
import app.cash.paparazzi.internal.*

internal fun registerDefaultMethodInterceptors(logger: PaparazziLogger) {
    registerFontLookupInterceptionIfResourceCompatDetected(logger)
    registerViewEditModeInterception()
    registerMatrixMultiplyInterception()
    registerChoreographerDelegateInterception()
}

/**
 * Current workaround for supporting custom fonts when constructing views in code. This check
 * may be used or expanded to support other cases requiring similar method interception
 * techniques.
 *
 * See:
 * https://github.com/cashapp/paparazzi/issues/119
 * https://issuetracker.google.com/issues/156065472
 */
private fun registerFontLookupInterceptionIfResourceCompatDetected(logger: PaparazziLogger) {
    try {
        val resourcesCompatClass = Class.forName("androidx.core.content.res.ResourcesCompat")
        InterceptorRegistrar.addMethodInterceptor(
            resourcesCompatClass, "getFont", ResourcesInterceptor::class.java
        )
    } catch (e: ClassNotFoundException) {
        logger.verbose("ResourceCompat not found on classpath")
    }
}

private fun registerViewEditModeInterception() {
    val viewClass = Class.forName("android.view.View")
    InterceptorRegistrar.addMethodInterceptor(
        viewClass, "isInEditMode", EditModeInterceptor::class.java
    )
}

private fun registerMatrixMultiplyInterception() {
    val matrixClass = Class.forName("android.opengl.Matrix")
    InterceptorRegistrar.addMethodInterceptors(
        matrixClass,
        setOf(
            "multiplyMM" to MatrixMatrixMultiplicationInterceptor::class.java,
            "multiplyMV" to MatrixVectorMultiplicationInterceptor::class.java
        )
    )
}

private fun registerChoreographerDelegateInterception() {
    val choreographerDelegateClass = Class.forName("android.view.Choreographer_Delegate")
    InterceptorRegistrar.addMethodInterceptor(
        choreographerDelegateClass, "getFrameTimeNanos", ChoreographerDelegateInterceptor::class.java
    )
}
