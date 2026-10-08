package app.waypoint.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.waypoint.R
import app.waypoint.core.base.BaseActivity
import app.waypoint.core.util.enterRise
import app.waypoint.data.geo.Geo
import app.waypoint.data.repo.PlaceResult
import app.waypoint.data.repo.RecentsRepo
import app.waypoint.data.repo.SearchRepo
import app.waypoint.databinding.ActivitySearchBinding
import app.waypoint.databinding.ItemPlaceBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class SearchActivity : BaseActivity<ActivitySearchBinding>() {

    private val repo: SearchRepo by inject()
    private val recents: RecentsRepo by inject()

    private var searchJob: Job? = null

    override fun inflateBinding(inflater: LayoutInflater): ActivitySearchBinding =
        ActivitySearchBinding.inflate(inflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padToInsets(binding.searchRoot, top = true)
        // keep the list above the keyboard (edge-to-edge + IME)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.searchRoot) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, maxOf(nav, ime))
            insets
        }

        binding.backBtn.setOnClickListener { finish() }
        binding.clearBtn.setOnClickListener {
            binding.searchField.setText("")
            binding.searchField.requestFocus()
        }

        binding.searchField.doAfterTextChanged { text ->
            val q = text?.toString().orEmpty()
            binding.clearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            searchJob?.cancel()
            if (q.isBlank()) {
                renderResults(emptyList(), fromRemote = false, query = "")
                return@doAfterTextChanged
            }
            searchJob = lifecycleScope.launch {
                delay(250)
                val results = repo.typeahead(q)
                renderResults(sortByDistance(results), fromRemote = false, query = q)
            }
        }

        binding.searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }

        observeRecents()

        binding.searchField.requestFocus()
        binding.searchField.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.searchField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submit() {
        val q = binding.searchField.text?.toString().orEmpty()
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val (results, remote) = repo.submit(q)
            renderResults(sortByDistance(results), fromRemote = remote, query = q)
        }
    }

    private fun sortByDistance(list: List<PlaceResult>): List<PlaceResult> {
        val from = session.target.value
        return list.sortedBy { Geo.haversine(from.lat, from.lon, it.lat, it.lon) }.take(5)
    }

    private fun renderResults(results: List<PlaceResult>, fromRemote: Boolean, query: String) {
        val hasQuery = query.isNotBlank()
        binding.resultsHead.visibility = if (hasQuery) View.VISIBLE else View.GONE
        binding.resultsContainer.removeAllViews()
        binding.osmCredit.visibility = if (hasQuery && fromRemote && results.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (hasQuery && results.isEmpty()) View.VISIBLE else View.GONE
        if (hasQuery && results.isEmpty()) {
            binding.emptyTitle.text = getString(R.string.search_empty_title, query)
        }
        val from = session.target.value
        results.forEachIndexed { i, p ->
            val row = ItemPlaceBinding.inflate(layoutInflater, binding.resultsContainer, true)
            row.placeIcon.setImageResource(R.drawable.ic_location_on)
            row.placeTitle.text = p.name
            row.placeSub.text = p.sub
            row.placeSub.visibility = if (p.sub.isBlank()) View.GONE else View.VISIBLE
            row.placeDist.text = Geo.fmtDist(Geo.haversine(from.lat, from.lon, p.lat, p.lon))
            row.root.setOnClickListener { deliver(p) }
            row.root.enterRise(i * 40L)
        }
    }

    private fun observeRecents() {
        lifecycleScope.launch {
            recents.observe().collect { list ->
                binding.recentHead.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                binding.recentContainer.removeAllViews()
                val from = session.target.value
                list.forEach { r ->
                    val row = ItemPlaceBinding.inflate(layoutInflater, binding.recentContainer, true)
                    row.placeIcon.setImageResource(R.drawable.ic_schedule)
                    row.placeTitle.text = r.name
                    row.placeSub.text = r.sub
                    row.placeSub.visibility = if (r.sub.isBlank()) View.GONE else View.VISIBLE
                    row.placeDist.text = Geo.fmtDist(Geo.haversine(from.lat, from.lon, r.lat, r.lon))
                    row.root.setOnClickListener {
                        deliver(PlaceResult(r.name, r.sub, r.lat, r.lon))
                    }
                }
            }
        }
    }

    private fun deliver(p: PlaceResult) {
        lifecycleScope.launch { recents.push(p.name, p.lat, p.lon, p.sub) }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra("lat", p.lat)
                .putExtra("lon", p.lon)
                .putExtra("name", p.name)
                .putExtra("sub", p.sub)
        )
        finish()
    }
}
