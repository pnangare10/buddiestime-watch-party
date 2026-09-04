package com.buddiestime.watchparty

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import java.util.GregorianCalendar

/**
 * Live YYYY-MM-DD mask for the birthday fields on the first-run form and in Settings.
 *
 * The mask only ever *formats* — it inserts the dashes as digits arrive and caps the
 * field at eight digits. It deliberately does not clamp or auto-correct mid-typing:
 * turning a freshly typed "1" into "01" fights someone who is on their way to typing
 * November, so an out-of-range month or an impossible day (Feb 30) is caught on submit
 * instead, where the error message can actually explain itself.
 *
 * Date validity is delegated to a non-lenient GregorianCalendar rather than hand-rolled
 * leap-year arithmetic — minSdk is 24 and the project has no core library desugaring, so
 * java.time is off the table here.
 */
object BirthdayInput {
    const val PATTERN = "YYYY-MM-DD"

    private const val MAX_DIGITS = 8
    private const val EARLIEST_YEAR = 1900

    /** True once eight digits are present *and* they name a real, plausible birthday. */
    fun isValid(raw: CharSequence): Boolean {
        val digits = digitsOf(raw)
        if (digits.length != MAX_DIGITS) return false

        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        if (year < EARLIEST_YEAR) return false

        val cal = GregorianCalendar()
        cal.isLenient = false
        cal.clear()
        cal.set(year, month - 1, day)
        val millis = try {
            cal.timeInMillis
        } catch (e: IllegalArgumentException) {
            return false
        }
        // A birthday in the future is a typo, not a birthday.
        return millis <= System.currentTimeMillis()
    }

    /**
     * Installs the mask on [field]. [onEdit] fires whenever the user changes the text, so
     * callers can clear a validation error the moment someone starts fixing it.
     */
    fun attach(field: EditText, onEdit: () -> Unit = {}) {
        field.addTextChangedListener(object : TextWatcher {
            /** Guards the re-entrant afterTextChanged our own edit provokes. */
            private var selfEdit = false

            /**
             * Index of the digit to swallow along with a backspaced dash, or -1. Without
             * this, backspacing over the separator in "1995-04" only removes the dash and
             * the mask instantly puts it back, so the key appears to do nothing.
             */
            private var dropDigitAt = -1

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (selfEdit) return
                dropDigitAt = if (count == 1 && after == 0 && s.getOrNull(start) == '-') {
                    digitsOf(s.subSequence(0, start)).length - 1
                } else {
                    -1
                }
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable) {
                if (selfEdit) return

                var digits = digitsOf(s)
                var caretDigit = digitsOf(s.subSequence(0, field.selectionEnd.coerceIn(0, s.length))).length
                if (dropDigitAt in digits.indices) {
                    digits = digits.removeRange(dropDigitAt, dropDigitAt + 1)
                    caretDigit = dropDigitAt
                }
                dropDigitAt = -1

                val formatted = withDashes(digits)
                onEdit()
                if (formatted == s.toString()) return

                selfEdit = true
                try {
                    s.replace(0, s.length, formatted)
                } finally {
                    selfEdit = false
                }
                field.setSelection(offsetAfterDigit(formatted, caretDigit))
            }
        })
    }

    private fun digitsOf(raw: CharSequence): String = raw.filter { it.isDigit() }.toString()

    private fun withDashes(digits: String): String = buildString {
        digits.take(MAX_DIGITS).forEachIndexed { i, c ->
            if (i == 4 || i == 6) append('-')
            append(c)
        }
    }

    /**
     * Where the caret belongs once [count] digits sit behind it. Counting digits rather
     * than characters is what keeps mid-string edits from throwing the caret to the end.
     */
    private fun offsetAfterDigit(text: String, count: Int): Int {
        if (count <= 0) return 0
        var seen = 0
        text.forEachIndexed { i, c ->
            if (c.isDigit()) {
                seen++
                if (seen == count) return i + 1
            }
        }
        return text.length
    }
}
