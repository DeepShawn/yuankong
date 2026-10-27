package com.aicode.lancmd

import android.app.Application
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ResponsiveLayoutTest {
    private fun layout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        view.layout(0,0,width,view.measuredHeight)
    }
    @Test fun toolGridReflowsWithoutLosingDestinations() {
        val context=RuntimeEnvironment.getApplication()
        val grid=ToolGrid(context)
        repeat(4) { grid.addView(TextView(context).apply { text="工具 $it"; minHeight=88 }) }
        layout(grid,360)
        assertEquals(4,grid.childCount)
        assertTrue(grid.getChildAt(2).top >= grid.getChildAt(0).bottom)
        layout(grid,720)
        assertEquals(grid.getChildAt(0).top,grid.getChildAt(3).top)
        assertTrue(grid.getChildAt(3).right <= 720)
    }
    @Test fun operationGroupStacksAndReturnsToColumns() {
        val context=RuntimeEnvironment.getApplication()
        val row=Ui.ActionRow(context)
        repeat(3) { row.addView(TextView(context).apply { text="操作 $it"; minHeight=48 },LinearLayout.LayoutParams(0,-2,1f)) }
        layout(row,320)
        assertEquals(LinearLayout.VERTICAL,row.orientation)
        assertTrue(row.getChildAt(1).top >= row.getChildAt(0).bottom)
        layout(row,600)
        assertEquals(LinearLayout.HORIZONTAL,row.orientation)
        assertTrue(row.getChildAt(2).right <= 600)
    }
}
