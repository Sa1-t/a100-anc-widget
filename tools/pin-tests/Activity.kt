package android.app
import android.content.Context
import android.os.Bundle
import android.widget.Button
open class Activity : Context() {
    val button = Button()
    var requested: Array<String>? = null
    var requestCode = 0
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {}
    fun setContentView(layout: Int) {}
    @Suppress("UNCHECKED_CAST")
    fun <T> findViewById(id: Int): T = button as T
    fun requestPermissions(permissions: Array<String>, code: Int) {
        requested = permissions; requestCode = code
    }
}
class AlertDialog {
    companion object { var pending: Builder? = null }
    class Builder(val context: Context) {
        var labels = emptyArray<String>()
        lateinit var select: (Any?, Int) -> Unit
        lateinit var negative: (Any?, Int) -> Unit
        lateinit var cancel: (Any?) -> Unit
        fun setTitle(title: String) = this
        fun setItems(items: Array<String>, listener: (Any?, Int) -> Unit) = apply { labels = items; select = listener }
        fun setNegativeButton(text: String, listener: (Any?, Int) -> Unit) = apply { negative = listener }
        fun setOnCancelListener(listener: (Any?) -> Unit) = apply { cancel = listener }
        fun show() { pending = this }
    }
}
