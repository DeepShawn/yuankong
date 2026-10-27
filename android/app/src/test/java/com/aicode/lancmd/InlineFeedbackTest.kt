package com.aicode.lancmd

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

class FeedbackHarnessActivity:FeatureActivity() {
    val first=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>()
    lateinit var group:LinearLayout
    override fun onCreate(state:Bundle?) {
        setTheme(R.style.Theme_LanCmd);super.onCreate(state)
        setup("按钮附近的结果","每个操作分别显示结果",scrollable=true)
        group=panel()
        group.addView(button("操作 A") { task { status("正在处理 A");first.await();status("A 已完成") } })
        group.addView(button("操作 B") { task { status("正在处理 B");second.await();status("B 已完成") } })
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineFeedbackTest {
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+4000
        while(System.currentTimeMillis()<end) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) }
        fail("inline result did not appear")
    }
    @Test fun concurrentResultsStayBesideTheirOwnButtonsWhenTheyCompleteOutOfOrder() {
        val controller=Robolectric.buildActivity(FeedbackHarnessActivity::class.java).setup();val activity=controller.get()
        val root=activity.window.decorView
        val buttons=all(root).filterIsInstance<com.google.android.material.button.MaterialButton>()
        val a=buttons.single { it.text=="操作 A" };val b=buttons.single { it.text=="操作 B" }
        a.performClick();b.performClick();shadowOf(Looper.getMainLooper()).idle()
        activity.second.complete(Unit);shadowOf(Looper.getMainLooper()).idle();activity.first.complete(Unit)
        waitFor { all(root).filterIsInstance<TextView>().any { it.text=="操作 A · A 已完成" } }
        val resultA=all(root).filterIsInstance<TextView>().single { it.text=="操作 A · A 已完成" }
        val resultB=all(root).filterIsInstance<TextView>().single { it.text=="操作 B · B 已完成" }
        assertSame(a.parent,resultA.parent);assertSame(b.parent,resultB.parent)
        assertEquals(activity.group.indexOfChild(a)+1,activity.group.indexOfChild(resultA))
        assertEquals(activity.group.indexOfChild(b)+1,activity.group.indexOfChild(resultB))
        root.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(891,View.MeasureSpec.EXACTLY));root.layout(0,0,411,891)
        val bitmap=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        File("build/ui-previews/inline-results.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        controller.pause().stop().destroy()
    }
    @Test fun rebuildingAnActionGroupDoesNotLoseItsPendingResult() {
        val controller=Robolectric.buildActivity(FeedbackHarnessActivity::class.java).setup();val activity=controller.get()
        all(activity.window.decorView).filterIsInstance<com.google.android.material.button.MaterialButton>().single { it.text=="操作 A" }.performClick()
        shadowOf(Looper.getMainLooper()).idle();activity.group.removeAllViews();activity.group.addView(TextView(activity).apply { text="刷新后的内容" })
        activity.first.complete(Unit)
        waitFor { all(activity.group).filterIsInstance<TextView>().any { it.text=="操作 A · A 已完成" } }
        controller.pause().stop().destroy()
    }
}
