package app.waypoint.core.base

import android.app.Activity
import android.view.View
import androidx.annotation.LayoutRes
import app.waypoint.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Shows a design-styled bottom sheet (24 dp top radius, 62 % scrim, drag handle in layout). */
fun Activity.showSheet(
    @LayoutRes layoutRes: Int,
    bind: (View, BottomSheetDialog) -> Unit
): BottomSheetDialog {
    val dialog = BottomSheetDialog(this, R.style.Wp_SheetDialog)
    val view = layoutInflater.inflate(layoutRes, null)
    dialog.setContentView(view)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    bind(view, dialog)
    dialog.show()
    return dialog
}
