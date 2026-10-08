package app.waypoint.automation

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import app.waypoint.R
import app.waypoint.core.base.BaseActivity
import app.waypoint.databinding.ActivityTaskerEditBinding
import app.waypoint.databinding.ItemOptionRowBinding
import java.util.Locale

/** Locale/Tasker EDIT activity: pick Set / Stop / Play route and the parameters. */
class TaskerEditActivity : BaseActivity<ActivityTaskerEditBinding>() {

    private var selected = 0 // 0 set · 1 stop · 2 route
    private val optionRows = mutableListOf<ItemOptionRowBinding>()

    override fun inflateBinding(inflater: LayoutInflater): ActivityTaskerEditBinding =
        ActivityTaskerEditBinding.inflate(inflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padToInsets(binding.taskerRoot, top = true, bottom = true)
        binding.backBtn.setOnClickListener { finish() }

        val labels = listOf(
            getString(R.string.tasker_action_set),
            getString(R.string.tasker_action_stop),
            getString(R.string.tasker_action_route)
        )
        labels.forEachIndexed { i, label ->
            val row = ItemOptionRowBinding.inflate(layoutInflater, binding.optionsContainer, true)
            row.optionLabel.text = label
            row.root.setOnClickListener {
                selected = i
                render()
            }
            optionRows.add(row)
        }

        // Prefill from the current target + any previous config
        val t = session.target.value
        binding.latField.setText(String.format(Locale.US, "%.6f", t.lat))
        binding.lonField.setText(String.format(Locale.US, "%.6f", t.lon))
        binding.nameField.setText(t.name)

        intent.getBundleExtra(TaskerFireReceiver.EXTRA_BUNDLE)?.let { prev ->
            when (prev.getString(TaskerFireReceiver.KEY_ACTION)) {
                "stop" -> selected = 1
                "route" -> {
                    selected = 2
                    binding.routeIdField.setText(
                        prev.getLong(TaskerFireReceiver.KEY_ROUTE_ID, 0L).toString()
                    )
                }
                else -> {
                    selected = 0
                    binding.latField.setText(prev.getDouble(TaskerFireReceiver.KEY_LAT, t.lat).toString())
                    binding.lonField.setText(prev.getDouble(TaskerFireReceiver.KEY_LON, t.lon).toString())
                    prev.getString(TaskerFireReceiver.KEY_NAME)?.let { binding.nameField.setText(it) }
                }
            }
        }

        binding.saveTasker.setOnClickListener { save() }
        render()
    }

    private fun render() {
        optionRows.forEachIndexed { i, row ->
            row.optionRadio.setImageResource(
                if (i == selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off
            )
        }
        binding.setFields.visibility = if (selected == 0) View.VISIBLE else View.GONE
        binding.routeFields.visibility = if (selected == 2) View.VISIBLE else View.GONE
    }

    private fun save() {
        val bundle = Bundle()
        val blurb: String
        when (selected) {
            1 -> {
                bundle.putString(TaskerFireReceiver.KEY_ACTION, "stop")
                blurb = getString(R.string.tasker_action_stop)
            }
            2 -> {
                val id = binding.routeIdField.text?.toString()?.toLongOrNull() ?: 0L
                bundle.putString(TaskerFireReceiver.KEY_ACTION, "route")
                bundle.putLong(TaskerFireReceiver.KEY_ROUTE_ID, id)
                blurb = getString(R.string.tasker_blurb_route, id)
            }
            else -> {
                val lat = binding.latField.text?.toString()?.toDoubleOrNull()
                val lon = binding.lonField.text?.toString()?.toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    say(getString(R.string.tasker_bad_coords))
                    return
                }
                val name = binding.nameField.text?.toString()?.trim().orEmpty().ifBlank { "Tasker" }
                bundle.putString(TaskerFireReceiver.KEY_ACTION, "set")
                bundle.putDouble(TaskerFireReceiver.KEY_LAT, lat)
                bundle.putDouble(TaskerFireReceiver.KEY_LON, lon)
                bundle.putString(TaskerFireReceiver.KEY_NAME, name)
                blurb = getString(R.string.tasker_blurb_set, name)
            }
        }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(TaskerFireReceiver.EXTRA_BUNDLE, bundle)
                .putExtra(TaskerFireReceiver.EXTRA_BLURB, blurb)
        )
        finish()
    }
}
