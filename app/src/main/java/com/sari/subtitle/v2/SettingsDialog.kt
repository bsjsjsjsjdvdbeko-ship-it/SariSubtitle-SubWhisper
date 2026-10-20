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


        // ═══════════════════════════════════════════════════════════
        // مفتاح Groq API
        // ═══════════════════════════════════════════════════════════
        root.addView(makeLabel(context, "مفتاح Groq API"))

        val groqApiKeyStatus = TextView(context).apply {
            textSize = 14f
            val isSaved = settings.hasGroqApiKey()
            text = if (isSaved) "الحالة: محفوظ ✓" else "الحالة: غير محفوظ ✗"
            setTextColor(if (isSaved) Color.GREEN else Color.RED)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }
        root.addView(groqApiKeyStatus)

        val groqApiKeyInput = android.widget.EditText(context).apply {
            hint = "أدخل مفتاح Groq الجديد (اختياري)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(groqApiKeyInput)

        root.addView(TextView(context).apply {
            text = "اتركه فارغاً للإبقاء على المفتاح الحالي."
            textSize = 11f
            setTextColor(Color.GRAY)
        })

        // زر إظهار المفتاح مؤقتاً
        val showKeyBtn = Button(context).apply {
            text = "إظهار المفتاح المدخل"
            setOnClickListener {
                if (groqApiKeyInput.inputType ==
                    (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
                    groqApiKeyInput.inputType = android.text.InputType.TYPE_CLASS_TEXT
                } else {
                    groqApiKeyInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        }
        root.addView(showKeyBtn)

        // زر حذف المفتاح
        root.addView(Button(context).apply {
            text = "حذف مفتاح Groq المحفوظ"
            setOnClickListener {
                android.app.AlertDialog.Builder(context)
                    .setTitle("حذف المفتاح")
                    .setMessage("هل تريد حذف مفتاح Groq المحفوظ؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف") { _, _ ->
                        settings.clearGroqApiKey()
                        groqApiKeyStatus.text = "الحالة: غير محفوظ ✗"
                        android.widget.Toast.makeText(context, "تم الحذف", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        })

        // مزود تحويل الصوت إلى نص (STT)
        root.addView(makeLabel(context, "مزود تحويل الصوت إلى نص (STT)"))
        val providerNames = arrayOf("Groq Whisper", "Deepgram Nova-3", "OpenAI Whisper")
        val providerSpinner = android.widget.Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, providerNames)
            setSelection(when (settings.sttProvider) { SubtitleSettings.STT_DEEPGRAM -> 1; SubtitleSettings.STT_OPENAI -> 2; else -> 0 })
        }
        root.addView(providerSpinner)
        root.addView(TextView(context).apply {
            text = "يمكنك الاختيار بين Groq وDeepgram وOpenAI. لكل مزود مفتاح وحدود/تكلفة مستقلة؛ OpenAI Whisper ليس مجانياً عادةً."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, 6, 0, 6)
        })

        root.addView(makeLabel(context, "مفتاح Deepgram API"))
        val deepgramStatus = TextView(context).apply {
            text = if (settings.hasDeepgramApiKey()) "الحالة: محفوظ ✓" else "الحالة: غير محفوظ ✗"
            textSize = 13f; setTextColor(if (settings.hasDeepgramApiKey()) Color.GREEN else Color.RED)
        }
        root.addView(deepgramStatus)
        val deepgramKeyInput = android.widget.EditText(context).apply {
            hint = "أدخل مفتاح Deepgram (اختياري)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(deepgramKeyInput)
        root.addView(TextView(context).apply {
            text = "اترك الحقل فارغاً للاحتفاظ بالمفتاح المحفوظ. المفاتيح تحفظ مشفرة عبر Android Keystore."
            textSize = 11f; setTextColor(Color.GRAY)
        })
        root.addView(Button(context).apply {
            text = "حذف مفتاح Deepgram المحفوظ"
            setOnClickListener {
                AlertDialog.Builder(context).setTitle("حذف المفتاح")
                    .setMessage("هل تريد حذف مفتاح Deepgram المحفوظ؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف") { _, _ ->
                        settings.clearDeepgramApiKey()
                        deepgramStatus.text = "الحالة: غير محفوظ ✗"; deepgramStatus.setTextColor(Color.RED)
                    }.show()
            }
        })

        root.addView(makeLabel(context, "مفتاح DeepL API (للترجمة)"))
        val deepLStatus = TextView(context).apply {
            text = if (settings.hasDeepLApiKey()) "الحالة: محفوظ ✓" else "الحالة: غير محفوظ ✗"
            textSize = 13f
            setTextColor(if (settings.hasDeepLApiKey()) Color.GREEN else Color.RED)
        }
        root.addView(deepLStatus)
        val deepLApiKeyInput = android.widget.EditText(context).apply {
            hint = "أدخل مفتاح DeepL (اختياري — جودة ترجمة 95%)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(deepLApiKeyInput)
        root.addView(TextView(context).apply {
            text = "اتركه فارغاً للاحتفاظ. DeepL مجاني 500K حرف/شهر. للخطة المجانية، المفتاح ينتهي بـ :fx"
            textSize = 11f
            setTextColor(Color.GRAY)
        })
        root.addView(Button(context).apply {
            text = "حذف مفتاح DeepL المحفوظ"
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("حذف المفتاح")
                    .setMessage("هل تريد حذف مفتاح DeepL المحفوظ؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف") { _, _ ->
                        settings.clearDeepLApiKey()
                        deepLStatus.text = "الحالة: غير محفوظ ✗"
                        deepLStatus.setTextColor(Color.RED)
                    }
                    .show()
            }
        })

        root.addView(makeLabel(context, "مفتاح OpenAI API"))
        val openAiStatus = TextView(context).apply {
            text = if (settings.hasOpenAiApiKey()) "الحالة: محفوظ ✓" else "الحالة: غير محفوظ ✗"
            textSize = 13f; setTextColor(if (settings.hasOpenAiApiKey()) Color.GREEN else Color.RED)
        }
        root.addView(openAiStatus)
        val openAiKeyInput = android.widget.EditText(context).apply {
            hint = "أدخل مفتاح OpenAI (اختياري)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(openAiKeyInput)
        root.addView(TextView(context).apply {
            text = "تنبيه: استخدام OpenAI API قد يتطلب رصيداً مدفوعاً. لا يوجد ضمان لاستخدام مجاني."
            textSize = 11f; setTextColor(Color.DKGRAY)
        })
        root.addView(Button(context).apply {
            text = "حذف مفتاح OpenAI المحفوظ"
            setOnClickListener {
                AlertDialog.Builder(context).setTitle("حذف المفتاح")
                    .setMessage("هل تريد حذف مفتاح OpenAI المحفوظ؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف") { _, _ ->
                        settings.clearOpenAiApiKey()
                        openAiStatus.text = "الحالة: غير محفوظ ✗"; openAiStatus.setTextColor(Color.RED)
                    }.show()
            }
        })

        // محرك المعالجة: الخيار يُحفظ محلياً، والمحرك الداخلي لا يستخدم SubWhisper.
        root.addView(makeLabel(context, "محرك المعالجة"))
        root.addView(TextView(context).apply {
            text = "هذا الاختيار يحدد مسار إنشاء SRT من الروابط. ترجمة ملف SRT تُدار من داخل التطبيق حالياً عبر خدمة ترجمة إنترنت منفصلة، وليست معالجة محلية بالكامل."
            textSize = 12f
            setTextColor(Color.DKGRAY)
        })
        val engineRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val engineButtons = mutableListOf<Button>()
        val engineLabels = listOf("داخلي Sari Subtitle", "SubWhisper الخارجي")
        engineLabels.forEachIndexed { i, label ->
            val btn = Button(context).apply {
                text = label
                textSize = 10f
                setOnClickListener {
                    settings.processingEngine = if (i == 0) SubtitleSettings.ENGINE_INTERNAL else SubtitleSettings.ENGINE_SUBWHISPER
                    updateSizeButtons(engineButtons, i)
                }
            }
            engineRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            engineButtons.add(btn)
        }
        root.addView(engineRow)
        updateSizeButtons(engineButtons, if (settings.processingEngine == SubtitleSettings.ENGINE_SUBWHISPER) 1 else 0)

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
                try {
                    val newKey = groqApiKeyInput.text.toString().trim()
                    if (newKey.isNotEmpty()) {
                        settings.groqApiKey = newKey
                        groqApiKeyStatus.text = "الحالة: محفوظ ✓"
                        groqApiKeyStatus.setTextColor(android.graphics.Color.GREEN)
                    }
                    val newDeepgramKey = deepgramKeyInput.text.toString().trim()
                    if (newDeepgramKey.isNotEmpty()) {
                        settings.deepgramApiKey = newDeepgramKey
                        deepgramStatus.text = "الحالة: محفوظ ✓"
                        deepgramStatus.setTextColor(Color.GREEN)
                    }
                    val newDeepLKey = deepLApiKeyInput.text.toString().trim()
                    if (newDeepLKey.isNotEmpty()) {
                        settings.deepLApiKey = newDeepLKey
                        deepLStatus.text = "الحالة: محفوظ ✓"
                        deepLStatus.setTextColor(Color.GREEN)
                    }
                    val newOpenAiKey = openAiKeyInput.text.toString().trim()
                    if (newOpenAiKey.isNotEmpty()) {
                        settings.openAiApiKey = newOpenAiKey
                        openAiStatus.text = "الحالة: محفوظ ✓"
                        openAiStatus.setTextColor(Color.GREEN)
                    }
                    settings.sttProvider = when (providerSpinner.selectedItemPosition) {
                        1 -> SubtitleSettings.STT_DEEPGRAM
                        2 -> SubtitleSettings.STT_OPENAI
                        else -> SubtitleSettings.STT_GROQ
                    }
                    overlay.applySettings(settings)
                    onSaved()
                } catch (e: Exception) {
                    groqApiKeyStatus.text = "خطأ: ${e.message}"
                    groqApiKeyStatus.setTextColor(android.graphics.Color.RED)
                }
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
