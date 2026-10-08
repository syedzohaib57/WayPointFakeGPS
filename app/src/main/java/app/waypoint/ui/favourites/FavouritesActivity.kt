package app.waypoint.ui.favourites

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.waypoint.R
import app.waypoint.core.base.BaseActivity
import app.waypoint.core.base.showSheet
import app.waypoint.core.util.enterRise
import app.waypoint.core.util.floatLoop
import app.waypoint.core.util.pressFx
import app.waypoint.data.db.FavouriteEntity
import app.waypoint.data.repo.FavouritesRepo
import app.waypoint.databinding.ActivityFavouritesBinding
import app.waypoint.databinding.ItemSheetActionBinding
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class FavouritesActivity : BaseActivity<ActivityFavouritesBinding>() {

    private val repo: FavouritesRepo by inject()
    private lateinit var adapter: FavouritesAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var dragging = false

    override fun inflateBinding(inflater: LayoutInflater): ActivityFavouritesBinding =
        ActivityFavouritesBinding.inflate(inflater)

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    val json = repo.exportJson()
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    say(getString(R.string.favs_exported, adapter.items.size))
                } catch (_: Exception) {
                    say(getString(R.string.favs_import_failed))
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    val count = contentResolver.openInputStream(uri)?.use { repo.importStream(it) } ?: 0
                    say(getString(R.string.favs_imported, count))
                } catch (_: Exception) {
                    say(getString(R.string.favs_import_failed))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padToInsets(binding.favRoot, top = true, bottom = true)

        adapter = FavouritesAdapter(
            onClick = { f -> showActions(f) },
            onDragStart = { vh -> touchHelper.startDrag(vh) }
        )
        binding.favList.layoutManager = LinearLayoutManager(this)
        binding.favList.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                dragging = true
                return adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (dragging) {
                    dragging = false
                    lifecycleScope.launch { repo.persistOrder(adapter.items.toList()) }
                }
            }
        })
        touchHelper.attachToRecyclerView(binding.favList)

        binding.backBtn.setOnClickListener { finish() }
        binding.fab.pressFx()
        binding.fab.setOnClickListener { addCurrent() }
        binding.emptyAddBtn.pressFx()
        binding.emptyAddBtn.setOnClickListener { addCurrent() }
        binding.importBtn.setOnClickListener {
            importLauncher.launch(
                arrayOf("application/json", "application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")
            )
        }
        binding.exportBtn.setOnClickListener { exportLauncher.launch("waypoint-favourites.json") }
        binding.emptyPin.floatLoop()

        lifecycleScope.launch {
            repo.observe().collect { list ->
                if (!dragging) {
                    adapter.setItems(list)
                    binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.fab.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun addCurrent() {
        val t = session.target.value
        lifecycleScope.launch {
            repo.add(t.name, t.lat, t.lon)
            say(getString(R.string.favs_saved, t.name))
        }
    }

    private fun showActions(f: FavouriteEntity) {
        showSheet(R.layout.sheet_fav_actions) { v, dialog ->
            v.findViewById<android.widget.TextView>(R.id.sheetTitle).text = f.name
            val container = v.findViewById<android.widget.LinearLayout>(R.id.actionsContainer)

            fun action(icon: Int, label: Int, tint: Int, run: () -> Unit) {
                val row = ItemSheetActionBinding.inflate(layoutInflater, container, true)
                row.actionIcon.setImageResource(icon)
                row.actionIcon.imageTintList = ContextCompat.getColorStateList(this, tint)
                row.actionLabel.setText(label)
                row.root.setOnClickListener {
                    dialog.dismiss()
                    run()
                }
            }

            action(R.drawable.ic_near_me, R.string.fav_go, R.color.wp_text_mid) {
                deliver("go", f)
            }
            action(R.drawable.ic_play_arrow_fill, R.string.fav_mock, R.color.wp_accent) {
                deliver("mock", f)
            }
            action(R.drawable.ic_edit, R.string.fav_rename, R.color.wp_text_mid) {
                showRename(f)
            }
            action(R.drawable.ic_delete, R.string.fav_remove, R.color.wp_text_mid) {
                lifecycleScope.launch {
                    repo.remove(f)
                    say(getString(R.string.favs_removed, f.name))
                }
            }
        }
    }

    private fun showRename(f: FavouriteEntity) {
        showSheet(R.layout.sheet_rename) { v, dialog ->
            val field = v.findViewById<EditText>(R.id.nameField)
            field.setText(f.name)
            field.setSelection(f.name.length)
            field.requestFocus()
            field.post {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
            v.findViewById<android.widget.TextView>(R.id.saveBtn).setOnClickListener {
                val name = field.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        repo.rename(f, name)
                        say(getString(R.string.favs_renamed, name))
                    }
                }
                dialog.dismiss()
            }
        }
    }

    private fun deliver(action: String, f: FavouriteEntity) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra("action", action)
                .putExtra("lat", f.lat)
                .putExtra("lon", f.lon)
                .putExtra("name", f.name)
                .putExtra("sub", "")
        )
        finish()
    }
}
