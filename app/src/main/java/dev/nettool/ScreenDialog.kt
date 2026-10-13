package dev.nettool

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Bounds scrollable dialogs to the usable display instead of the dialog's wrap-content height. */
@Composable internal fun ScreenDialog(onClose:()->Unit,content:@Composable ()->Unit) {
    val configuration=LocalConfiguration.current
    val context=LocalContext.current
    val height=if(Build.VERSION.SDK_INT>=30) {
        val metrics=(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics
        val bars=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        with(LocalDensity.current) {(metrics.bounds.height()-bars.top-bars.bottom).toDp()}
    } else configuration.screenHeightDp.dp
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxWidth().height(height)) {content()}
    }
}
