package app.studylens

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service used only to take single screenshots when the user taps the bubble.
 * It reads no screen content or events on its own.
 */
class ShotService : AccessibilityService() {
    companion object {
        var instance: ShotService? = null

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** One screenshot of the whole display, delivered on the main thread (null on failure). */
    fun shot(done: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                done(bmp)
            }
            override fun onFailure(errorCode: Int) { done(null) }
        })
    }
}
