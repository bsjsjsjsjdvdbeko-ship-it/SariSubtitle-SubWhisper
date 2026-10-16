package com.sari.subtitle.v2

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * حوار إعدادات الترجمة.
 * يعرض التحكم في الحجم/الموضع/الألوان + معاينة فورية.
 */
object SettingsDialog {

    fun show(
        context: Context,
        settings: SubtitleSettings,
        overlay: SubtitleOverlay,
        onSaved: () -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
        }

        // عنوان
        root.addView(makeTitle(context, "⚙️ إعدادات الترجمة"))

        // حجم الخط
        root.addView(makeLabel(context, "حجم الخط"))
        val sizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val sizeButtons = mutableListOf<Button>()
        SubtitleSettings.TEXT_SIZE_LABELS.forEachIndexed { i, label ->
            val btn = Button(context).apply {
                text = label
                textSize = 11f
                setOnClickListener {
                    settings.textSizeIndex = i
                    updateSizeButtons(sizeButtons, i)
                    preview(settings, overlay)
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            sizeRow.addView(btn, lp)
            sizeButtons.add(btn)
        }
        root.addView(sizeRow)
        updateSizeButtons(sizeButtons, settings.textSizeIndex)

        // الموضع
        root.addView(makeLabel(context, "موضع الترجمة"))
        val posRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val posButtons = mutableListOf<Button>()
        SubtitleSettings.POSITION_LABELS.forEachIndexed { i, label ->
            val btn = Button(context).apply {
                text = label
                textSize = 12f
                setOnClickListener {
                    settings.position = i
                    updateSizeButtons(posButtons, i)
                    preview(settings, overlay)
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            posRow.addView(btn, lp)
            posButtons.add(btn)
        }
        root.addView(posRow)
        updateSizeButtons(posButtons, settings.position)

        // لون النص
        root.addView(makeLabel(context, "لون النص"))
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val colorButtons = mutableListOf<Button>()
        SubtitleSettings.TEXT_COLORS.forEachIndexed { i, color ->
            val btn = Button(context).apply {
                text = "●"
                textSize = 22f
                setTextColor(color)
                setOnClickListener {
                    settings.textColorIndex = i
                    updateSizeButtons(colorButtons, i)
                    preview(settings, overlay)
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            colorRow.addView(btn, lp)
            colorButtons.add(btn)
        }
        root.addView(colorRow)
        updateSizeButtons(colorButtons, settings.textColorIndex)

        // لون الخلفية
        root.addView(makeLabel(context, "خلفية الترجمة"))
        val bgRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val bgButtons = mutableListOf<Button>()
        SubtitleSettings.BG_COLOR_LABELS.forEachIndexed { i, label ->
            val btn = Button(context).apply {
                text = label
                textSize = 10f
                setOnClickListener {
                    settings.bgColorIndex = i
                    updateSizeButtons(bgButtons, i)
                    preview(settings, overlay)
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            bgRow.addView(btn, lp)
            bgButtons.add(btn)
        }
        root.addView(bgRow)
        updateSizeButtons(bgButtons, settings.bgColorIndex)

        // خط عريض
        root.addView(makeLabel(context, "نمط الخط"))
        val boldRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val boldButtons = mutableListOf<Button>()
        arrayOf("عادي", "عريض").forEachIndexed { i, label ->
            val btn = Button(context).apply {
                text = label
                setOnClickListener {
                    settings.bold = (i == 1)
                    updateSizeButtons(boldButtons, i)
                    preview(settings, overlay)
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            boldRow.addView(btn, lp)
            boldButtons.add(btn)
        }
        root.addView(boldRow)
        updateSizeButtons(boldButtons, if (settings.bold) 1 else 0)

        // ScrollView
        val scroll = ScrollView(context).apply {
            addView(root)
        }

        // الحوار
        AlertDialog.Builder(context)
            .setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                overlay.applySettings(settings)
                onSaved()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun makeTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 20, 0, 20)
            gravity = Gravity.CENTER
        }

    private fun makeLabel(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setPadding(0, 20, 0, 6)
            setTextColor(Color.DKGRAY)
        }

    private fun updateSizeButtons(buttons: List<Button>, selected: Int) {
        buttons.forEachIndexed { i, btn ->
            btn.alpha = if (i == selected) 1.0f else 0.5f
        }
    }

    private fun preview(settings: SubtitleSettings, overlay: SubtitleOverlay) {
        overlay.applySettings(settings)
        overlay.preview("معاينة الترجمة")
    }
}
