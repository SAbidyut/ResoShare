package net.rapti.resoshare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real adaptive launcher icon (circle and squircle masks) to build/screens/icon. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconRenderTest {
    companion object { init { FakeAndroidKeyStore.install() } }

    @Test
    fun renderLauncherIcon() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val d = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 432
        val out = Bitmap.createBitmap(size * 2 + 60, size + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(233, 237, 243))
        listOf(0f to "circle", 0.3f to "squircle").forEachIndexed { i, (radius, _) ->
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(layer)
            val clip = Path().apply {
                if (radius == 0f) addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
                else addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * radius, size * radius, Path.Direction.CW)
            }
            c.clipPath(clip)
            d.background.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
            d.background.draw(c)
            d.foreground.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
            d.foreground.draw(c)
            canvas.drawBitmap(layer, 20f + i * (size + 20f), 20f, null)
        }
        val f = File("build/screens/icon/launcher.png").apply { parentFile?.mkdirs() }
        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 0)
    }
}
