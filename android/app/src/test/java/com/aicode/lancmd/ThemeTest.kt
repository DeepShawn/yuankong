package com.aicode.lancmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.toArgb
import com.aicode.lancmd.net.ConnectionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ThemeTest {
    @After fun tearDown() {
        ConnectionManager.stop()
        Prefs.saveTheme(RuntimeEnvironment.getApplication(), "", "")
    }

    @Test fun everyPaletteResolvesOnHomeAndFeaturePages() {
        val app = RuntimeEnvironment.getApplication()
        val combos = Themes.accents.map { it to Themes.tones[0] } + Themes.tones.drop(1).map { Themes.accents[0] to it }
        for ((accent, tone) in combos) {
            Prefs.saveTheme(app, accent.key, tone.key)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            val expected=if(tone.light)accent.lightSwatch else accent.swatch
            assertEquals(accent.label, expected, Themes.color(activity, R.color.accent))
            assertEquals(tone.label, tone.swatch, Themes.color(activity, R.color.bg))
            // 状态色固定，不随主题变化
            if(!tone.light)assertEquals(app.getColor(R.color.danger), Themes.color(activity, R.color.danger))
            else assertTrue(ColorUtils.calculateContrast(Themes.color(activity,R.color.danger),Themes.color(activity,R.color.surface))>=4.5)
            render(activity.window.decorView, "theme-${accent.key}-${tone.key}")
            controller.pause().stop().destroy()

            val intent = android.content.Intent(app, MediaActivity::class.java).putExtra("kind", "screen")
            val feature = Robolectric.buildActivity(MediaActivity::class.java, intent).setup()
            assertEquals(expected, Themes.color(feature.get(), R.color.accent))
            feature.pause().stop().destroy()
        }
    }

    class PaletteActivity:MiuixActivity()
    @Test fun everyLightAccentHasReadableNativeAndComposeColors() {
        val app=RuntimeEnvironment.getApplication()
        for(tone in Themes.tones.filter{it.light})for(accent in Themes.accents) {
            Prefs.saveTheme(app,accent.key,tone.key)
            val controller=Robolectric.buildActivity(PaletteActivity::class.java).setup();val page=controller.get()
            try {
                val surface=Themes.color(page,R.color.surface)
                for(id in listOf(R.color.text,R.color.dim,R.color.accent,R.color.danger,R.color.warn,R.color.ok)) {
                    assertTrue("${tone.key}/${accent.key}/$id must contrast with the light surface",ColorUtils.calculateContrast(Themes.color(page,id),surface)>=4.5)
                }
                assertTrue(ColorUtils.calculateContrast(Themes.color(page,R.color.on_accent),Themes.color(page,R.color.accent))>=4.5)
                val colors=lanCmdColors(page)
                assertEquals(surface,colors.surfaceContainer.toArgb())
                assertEquals(Themes.color(page,R.color.text),colors.onSurface.toArgb())
                assertTrue(page.window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR!=0)
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun pickerSavesChoiceAndHomeKeepsConsoleAcrossRecreate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.onEvent("主题切换前的日志", ConnectionManager.LogKind.INFO)
        shadowOf(Looper.getMainLooper()).idle()

        ThemeDialog(activity).show()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        find(dialog.window!!.decorView) { it.contentDescription == "主题色 紫罗兰" }!!.performClick()
        find(dialog.window!!.decorView) { it.contentDescription == "背景色调 纯黑" }!!.performClick()
        render(dialog.window!!.decorView, "theme-picker")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("violet", Prefs.accent(activity))
        assertEquals("black", Prefs.tone(activity))

        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        val recreated = controller.get()
        assertEquals(0xFFA98BFF.toInt(), Themes.color(recreated, R.color.accent))
        assertTrue(recreated.consoleText.contains("主题切换前的日志"))
        controller.pause().stop().destroy()
    }

    private fun find(view: View, match: (View) -> Boolean): View? {
        if (match(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun render(view: View, name: String, width: Int = 411, height: Int = 891) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, width, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
