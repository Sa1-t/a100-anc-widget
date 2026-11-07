package android.widget
import android.content.Context
class Button {
    private lateinit var listener: (Any?) -> Unit
    fun setOnClickListener(value: (Any?) -> Unit) { listener = value }
    fun click() = listener(null)
}
class TextView {
    var text: String = ""
}
class Toast(private val text: String) {
    companion object {
        const val LENGTH_LONG = 1
        val messages = mutableListOf<String>()
        fun makeText(context: Context, text: String, duration: Int) = Toast(text)
    }
    fun show() { messages.add(text) }
}
