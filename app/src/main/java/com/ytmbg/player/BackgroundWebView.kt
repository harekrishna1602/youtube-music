package com.ytmbg.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

class BackgroundWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    var allowBackgroundPlayback: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (allowBackgroundPlayback && visibility != View.VISIBLE) {
            // Keep WebView media engine active when screen turns off or app moves to background
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
