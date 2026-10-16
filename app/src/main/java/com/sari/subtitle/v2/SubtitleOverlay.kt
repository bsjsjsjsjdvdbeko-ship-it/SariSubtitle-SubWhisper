package com.sari.subtitle.v2

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * طبقة شفافة لعرض الترجمة فوق WebView.
 * تدعم الإعدادات: حجم، لون، خلفية، موضع، خط عريض.
 */
class SubtitleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val textView: TextView

    init {
        isClickable = false
        isFocusable = false

        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(28, 18, 28, 18)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            text = "Sari Subtitle"
            visibility = View.GONE
        }

        val lp = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 70
            leftMargin = 20
            rightMargin = 20
        }

        addView(textView, lp)
    }

    /**
     * تطبيق الإعدادات.
     */
    fun applySettings(settings: SubtitleSettings) {
        // حجم الخط
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.textSize)

        // لون النص
        textView.setTextColor(settings.textColor)

        // خلفية
        textView.setBackgroundColor(settings.bgColor)

        // عريض
        textView.typeface = if (settings.bold) {
            Typeface.DEFAULT_BOLD
        } else {
            Typeface.DEFAULT
        }

        // ظل: قوي على نص أبيض/فاتح، ضعيف على خلفية شفافة
        val shadowRadius = if (settings.bgColor == Color.TRANSPARENT) 10f else 4f
        textView.setShadowLayer(shadowRadius, 0f, 2f, Color.BLACK)

        // الموضع
        val lp = textView.layoutParams as LayoutParams
        when (settings.position) {
            SubtitleSettings.POS_TOP -> {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.topMargin = 70
                lp.bottomMargin = 0
            }
            SubtitleSettings.POS_CENTER -> {
                lp.gravity = Gravity.CENTER
                lp.topMargin = 0
                lp.bottomMargin = 0
            }
            else -> {
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.topMargin = 0
                lp.bottomMargin = 70
            }
        }
        textView.layoutParams = lp
    }

    fun show(text: String) {
        textView.text = text
        textView.visibility = View.VISIBLE
        visibility = View.VISIBLE
    }

    fun hide() {
        textView.visibility = View.GONE
        visibility = View.GONE
    }

    /**
     * عرض مؤقت للنص (للمعاينة في الإعدادات).
     */
    fun preview(text: String) {
        val prevVis = textView.visibility
        textView.text = text
        textView.visibility = View.VISIBLE
        visibility = View.VISIBLE
        postDelayed({
            if (prevVis == View.GONE) {
                textView.visibility = View.GONE
                visibility = View.GONE
            }
        }, 2000)
    }
}
