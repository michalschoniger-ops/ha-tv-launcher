package org.hometv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/** Explicit, one-shot input. The system speech UI owns the microphone permission and provider. */
class TvAssistantInput(
    private val activity: Activity,
    private val openDashboardAssistant: () -> Boolean,
    private val sendText: (String) -> Boolean,
    private val isDark: () -> Boolean = { true },
) {
    companion object { const val SPEECH_REQUEST = 4701 }

    fun showChoice() {
        if (!openDashboardAssistant()) return
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Asistent HA TV Launcher")
            .setMessage("Jak chceš zadat povel? Hlasový vstup otevře systémové rozpoznávání řeči až po tvém stisknutí.")
            .setPositiveButton("Hlasový povel") { _, _ -> recognizeSpeech() }
            .setNegativeButton("Napsat povel") { _, _ -> showTextInput() }
            .setNeutralButton("Zrušit", null)
            .show()
        NativeDialogStyle.apply(dialog, activity, isDark())
    }

    @Suppress("DEPRECATION")
    private fun recognizeSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Například: Otevři iPrima a spusť poslední díl Zrádců")
        }
        try { activity.startActivityForResult(intent, SPEECH_REQUEST) }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "Rozpoznávání řeči není dostupné. Povel můžeš napsat.", Toast.LENGTH_LONG).show()
            showTextInput()
        } catch (_: SecurityException) {
            Toast.makeText(activity, "Hlasový vstup se nepodařilo otevřít. Povel můžeš napsat.", Toast.LENGTH_LONG).show()
            showTextInput()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != SPEECH_REQUEST) return false
        if (resultCode != Activity.RESULT_OK) return true // Cancellation never retries or sends a command.
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (text.isNullOrBlank()) {
            Toast.makeText(activity, "Povel se nepodařilo rozpoznat. Můžeš ho napsat.", Toast.LENGTH_LONG).show()
            showTextInput()
        } else sendText(text.take(1000))
        return true
    }

    private fun showTextInput() {
        val input = EditText(activity).apply {
            hint = "Co chceš na televizi udělat?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
            setSingleLine(false)
        }
        val metrics = activity.resources.displayMetrics
        val padding = (22 * minOf(metrics.widthPixels / 1440f, metrics.heightPixels / 810f)).toInt()
        val body = LinearLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Napsat povel")
            .setView(body)
            .setPositiveButton("Odeslat", null)
            .setNegativeButton("Zrušit", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim().take(1000)
                if (text.isBlank()) input.error = "Napiš povel."
                else if (sendText(text)) dialog.dismiss()
            }
            input.requestFocus()
        }
        dialog.show()
        NativeDialogStyle.apply(dialog, activity, isDark())
    }
}
