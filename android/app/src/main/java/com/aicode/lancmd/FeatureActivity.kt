package com.aicode.lancmd

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.asContextElement
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.CoroutineContext

abstract class FeatureActivity : AppCompatActivity() {
    protected lateinit var body: LinearLayout
    protected lateinit var statusText: TextView
    private data class Feedback(val parent:LinearLayout,val anchor:View,val index:Int,val result:TextView,var title:String)
    private data class FeedbackScope(val target:Feedback?)
    private val feedbacks=java.util.IdentityHashMap<View,Feedback>()
    private val feedbackOwner=ThreadLocal<FeedbackScope?>()
    private var lastFeedback:Feedback?=null
    private var feedbackHidden=false
    private var inlineStatus=false
    protected open val inlineResults=true
    private var topDetails:LinearLayout?=null
    private var topDetailsText:TextView?=null
    private var topDetailsScroll:ScrollView?=null
    private var topDetailsButton:MaterialButton?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        AppVisibility.install(application)
    }

    protected fun setup(title: String, description: String, scrollable: Boolean = false) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }
        val toolbar = MaterialToolbar(this).apply {
            this.title = title
            setTitleTextAppearance(this@FeatureActivity, R.style.TextAppearance_LanCmd_ToolbarTitle)
            setTitleTextColor(color(R.color.text))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationIconTint(color(R.color.text))
            setNavigationOnClickListener { navigateBack() }
            menu.add("全部工具").apply { setIcon(R.drawable.ic_more);setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS) }
            setOnMenuItemClickListener { ToolNavigator.show(this@FeatureActivity);true }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.PAD_PAGE), 0, dp(Ui.PAD_PAGE), dp(12))
        }
        if (scrollable) {
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        statusText = label(description, Ui.TEXT_CAPTION, R.color.dim).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.status_bg)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(Ui.GAP_SECTION) })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
    }

    protected fun label(text: String, size: Float = Ui.TEXT_ITEM, tint: Int = R.color.text): TextView =
        Ui.text(this, text, size, tint)

    /** 卡片或分组标题。 */
    protected fun title(text: String): TextView = Ui.text(this, text, Ui.TEXT_TITLE, R.color.text)

    /** 次要说明文字，紧跟在标题或控件之后。 */
    protected fun caption(text: String): TextView = Ui.text(this, text, Ui.TEXT_CAPTION, R.color.dim)

    /** 细分隔线，用于列表条目之间。 */
    protected fun divider(): View = Ui.divider(this)

    /**
     * 结构化列表条目：标题 + 说明（可选）+ 右侧附加视图。
     * 取代以往「多行 label 叠在一起」的写法，让每条记录成为可辨认的一行。
     */
    protected fun listRow(
        title: String,
        detail: String? = null,
        tint: Int = R.color.text,
        icon: Int = 0,
        trailing: View? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(Ui.GAP_TIGHT), 0, dp(Ui.GAP_TIGHT))
        }
        if (icon != 0) row.addView(android.widget.ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, title, Ui.TEXT_ITEM, tint))
        if (!detail.isNullOrBlank()) texts.addView(caption(detail), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        return row
    }

    /** 带名称的输入控件：控件上方给出用途说明，不再只靠灰色 hint。 */
    protected fun fieldRow(name: String, field: View): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.sectionLabel(this, name))
        box.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return box
    }

    /** 并排的操作按钮组，自动等分并留出间距。 */
    protected fun actions(vararg items: MaterialButton): LinearLayout {
        val row = Ui.ActionRow(this)
        items.forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        return row
    }

    /** 空列表提示：居中弱化文字，而不是留白或一行普通说明。 */
    protected fun emptyState(text: String): TextView = caption(text).apply {
        gravity = android.view.Gravity.CENTER
        setPadding(dp(12), dp(22), dp(12), dp(22))
    }

    protected open fun navigateBack() { finish() }

    /** 统一输入框外观：圆角描边底、聚焦时高亮、主题文字与提示色。 */
    protected fun styleInput(view: EditText): EditText = view.apply {
        setBackgroundResource(R.drawable.input_bg)
        setTextColor(color(R.color.text))
        setHintTextColor(color(R.color.dim))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        textSize = Ui.TEXT_BODY
        if (minHeight < dp(Ui.TOUCH)) minHeight = dp(Ui.TOUCH)
    }

    /** 统一复选框外观：主题文字色与选中时的强调色勾选。 */
    protected fun styleCheck(view: CheckBox): CheckBox = view.apply {
        setTextColor(color(R.color.text))
        textSize = Ui.TEXT_BODY
        minHeight = dp(Ui.TOUCH)
        setPadding(dp(8), dp(6), 0, dp(6))
        buttonTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(R.color.accent), color(R.color.dim)))
    }

    /** 统一下拉框外观：圆角描边底与内边距，弹出项保持系统默认。 */
    protected fun styleSpinner(view: Spinner): Spinner = view.apply {
        setBackgroundResource(R.drawable.input_bg)
        minimumHeight = dp(Ui.TOUCH)
        if (paddingStart < dp(14)) setPadding(dp(14), paddingTop, dp(14), paddingBottom)
    }

    /** 并排等分的按钮自动留出间距，避免相邻按钮贴在一起。 */
    private class SpacedButton(context: android.content.Context) : MaterialButton(context) {
        override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
            if (parent !is Ui.ActionRow && params is LinearLayout.LayoutParams && params.width == 0 && params.weight > 0f &&
                params.leftMargin == 0 && params.rightMargin == 0) {
                val gap = (3 * resources.displayMetrics.density).toInt()
                params.leftMargin = gap; params.rightMargin = gap
            }
            super.setLayoutParams(params)
        }
    }

    protected fun button(text: String, action: () -> Unit): MaterialButton = SpacedButton(this).apply {
        this.text = text
        Ui.styleSecondary(this)
        setOnClickListener {
            val previous=feedbackOwner.get()
            val selected=feedbackFor(this)
            lastFeedback=selected;feedbackOwner.set(FeedbackScope(selected))
            try { action() } finally { feedbackOwner.set(previous) }
        }
    }

    protected fun primary(button: MaterialButton): MaterialButton = Ui.stylePrimary(button)

    /** 破坏性操作：危险色描边与文字。 */
    protected fun danger(button: MaterialButton): MaterialButton = Ui.styleDanger(button)

    /**
     * 内容卡片。子视图自动获得统一垂直间距，可选标题作为分区标签，
     * 页面因此不必再给每个元素手写 padding。
     */
    protected fun panel(heading: String? = null): LinearLayout {
        val content = Ui.Column(this).apply {
            setPadding(dp(Ui.PAD_CARD), dp(Ui.PAD_CARD), dp(Ui.PAD_CARD), dp(Ui.PAD_CARD))
        }
        val card = MaterialCardView(this).apply {
            radius = dp(Ui.RADIUS_CARD).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.outline_soft)
            setCardBackgroundColor(color(R.color.surface))
            addView(content)
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ui.GAP_SECTION) })
        if (heading != null) content.addView(Ui.sectionLabel(this, heading))
        return content
    }

    protected suspend fun request(command: String, params: JSONObject = JSONObject(), timeout: Long = 30_000): JSONObject {
        val result = ConnectionManager.send(command, params, timeout)
        if (!result.optBoolean("ok")) {
            val error = result.optString("error", "操作失败")
            throw IOException(if (error.contains("未知命令")) "$error，请更新电脑端至 1.5.0" else error)
        }
        return result
    }

    protected fun feedbackContext():CoroutineContext=feedbackOwner.asContextElement(feedbackOwner.get() ?: FeedbackScope(lastFeedback))

    private fun feedbackFor(button:View):Feedback? {
        if(!inlineResults)return null
        var anchor=button
        var parent=anchor.parent
        while(parent is LinearLayout && parent.orientation==LinearLayout.HORIZONTAL) { anchor=parent;parent=anchor.parent }
        if(parent !is LinearLayout || parent.orientation!=LinearLayout.VERTICAL)return null
        val existing=feedbacks[button]
        if(existing!=null) { existing.title=(button as? TextView)?.text.toString();return existing }
        val label=label("",12f,R.color.accent).apply {
            visibility=View.GONE;maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(dp(8),dp(6),dp(8),dp(6));setBackgroundResource(R.drawable.status_bg)
            contentDescription="操作结果";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener {
                val expanded=maxLines==Int.MAX_VALUE
                maxLines=if(expanded)4 else Int.MAX_VALUE
                ellipsize=if(expanded)android.text.TextUtils.TruncateAt.END else null
                announceForAccessibility(if(expanded)"结果已收起" else "结果已展开")
            }
        }
        return Feedback(parent,anchor,parent.indexOfChild(anchor),label,(button as? TextView)?.text.toString()).also { feedbacks[button]=it }
    }
    protected fun hideInlineFeedback(hidden:Boolean) {
        feedbackHidden=hidden
        feedbacks.values.forEach { it.result.visibility=if(hidden || it.result.text.isEmpty())View.GONE else View.VISIBLE }
        statusText.visibility=if(hidden || inlineStatus)View.GONE else View.VISIBLE
    }
    private fun attachedToBody(view:View):Boolean {
        var current:View?=view
        while(current!=null) { if(current===body)return true;current=current.parent as? View }
        return false
    }

    protected fun task(block: suspend () -> Unit): Job = lifecycleScope.launch(feedbackContext()) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status(e.message ?: "操作失败", true) }
    }

    internal fun fileOperationNotice(text:String)=status(text)

    protected fun status(text: String, error: Boolean = false) {
        if (isDestroyed) return
        statusText.text = text
        statusText.setTextColor(color(if (error) R.color.danger else R.color.dim))
        val feedback=if(inlineResults)feedbackOwner.get()?.target else null
        inlineStatus=feedback!=null && attachedToBody(feedback.parent)
        statusText.visibility=if(feedbackHidden || inlineStatus)View.GONE else View.VISIBLE
        if(inlineStatus && feedback!=null) {
            if(feedback.result.parent!==feedback.parent) {
                (feedback.result.parent as? ViewGroup)?.removeView(feedback.result)
                val index=feedback.parent.indexOfChild(feedback.anchor).takeIf { it>=0 } ?: feedback.index
                feedback.parent.addView(feedback.result,(index+1).coerceIn(0,feedback.parent.childCount),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(3);bottomMargin=dp(6) })
            }
            feedback.result.text="${feedback.title} · $text"
            feedback.result.setTextColor(color(if(error)R.color.danger else R.color.accent))
            feedback.result.visibility=if(feedbackHidden)View.GONE else View.VISIBLE
        }
    }

    protected fun reportTopResults(summary:String,lines:List<String>,failed:Boolean=false) {
        status(summary,failed)
        if(topDetails==null) {
            val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            val content=label("",12f,R.color.dim).apply { setTextIsSelectable(true);setPadding(dp(8),dp(6),dp(8),dp(6));contentDescription="逐项操作结果" }
            val scroll=ScrollView(this).apply { addView(content);visibility=View.GONE;setBackgroundResource(R.drawable.status_bg) }
            val expand=button("展开逐项结果") {
                scroll.visibility=if(scroll.visibility==View.VISIBLE)View.GONE else View.VISIBLE
                topDetailsButton?.text=if(scroll.visibility==View.VISIBLE)"收起逐项结果" else "展开逐项结果"
            }.apply { textSize=12f;minHeight=dp(48) }
            box.addView(expand);box.addView(scroll,LinearLayout.LayoutParams(-1,dp(104)))
            body.addView(box,1)
            topDetails=box;topDetailsText=content;topDetailsScroll=scroll;topDetailsButton=expand
        }
        topDetailsScroll?.layoutParams=topDetailsScroll?.layoutParams?.apply { height=dp((lines.size*20+12).coerceIn(48,104)) }
        topDetailsText?.text=lines.joinToString("\n").take(100_000)
        topDetails?.visibility=if(lines.isEmpty())View.GONE else View.VISIBLE
        topDetailsScroll?.visibility=if(failed)View.VISIBLE else View.GONE
        topDetailsButton?.text=if(failed)"收起逐项结果" else "展开逐项结果（${lines.size}）"
    }
    protected fun clearTopResults() { topDetails?.visibility=View.GONE }

    protected fun color(id: Int): Int = Themes.color(this, id)
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
