package eu.kanade.tachiyomi.ui.main

import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDialogController
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import dev.chrisbanes.insetter.applyInsetter
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.Migrations
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.preference.asImmediateFlow
import eu.kanade.tachiyomi.databinding.MainActivityBinding
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.ui.base.activity.BaseViewBindingActivity
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.FabController
import eu.kanade.tachiyomi.ui.base.controller.NoToolbarElevationController
import eu.kanade.tachiyomi.ui.base.controller.RootController
import eu.kanade.tachiyomi.ui.base.controller.TabbedController
import eu.kanade.tachiyomi.ui.base.controller.ToolbarLiftOnScrollController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.browse.BrowseController
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.download.DownloadController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.more.MoreController
import eu.kanade.tachiyomi.ui.recent.history.HistoryController
import eu.kanade.tachiyomi.ui.recent.updates.UpdatesController
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.launchUI
import eu.kanade.tachiyomi.util.system.InternalResourceHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import timber.log.Timber
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : BaseViewBindingActivity<MainActivityBinding>() {

    private lateinit var router: Router

    private val startScreenId by lazy {
        when (preferences.startScreen()) {
            2 -> R.id.nav_history
            3 -> R.id.nav_updates
            4 -> R.id.nav_browse
            else -> R.id.nav_library
        }
    }

    lateinit var tabAnimator: ViewHeightAnimator
    private lateinit var bottomNavAnimator: ViewHeightAnimator

    private var isConfirmingExit: Boolean = false
    private var isHandlingShortcut: Boolean = false

    private var fixedViewsToBottom = mutableMapOf<View, AppBarLayout.OnOffsetChangedListener>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Draw edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding.appbar.applyInsetter {
            type(navigationBars = true, statusBars = true) {
                padding(left = true, top = true, right = true)
            }
        }
        binding.rootFab.applyInsetter {
            type(navigationBars = true) {
                margin()
            }
        }
        binding.bottomNav.applyInsetter {
            type(navigationBars = true) {
                padding()
            }
        }

        // Make sure navigation bar is on bottom before we modify it
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            if (insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom > 0) {
                window.navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !InternalResourceHelper.getBoolean(this, "config_navBarNeedsScrim", true)
                ) {
                    Color.TRANSPARENT
                } else {
                    // Set navbar scrim 70% of navigationBarColor
                    getResourceColor(android.R.attr.navigationBarColor, 0.7F)
                }
            }
            insets
        }

        tabAnimator = ViewHeightAnimator(binding.tabs, 0L)
        bottomNavAnimator = ViewHeightAnimator(binding.bottomNav)

        // If bottom nav is hidden, make it visible again when the app bar is expanded
        binding.appbar.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
                if (verticalOffset == 0) {
                    showBottomNav(true)
                }
            }
        )

        // Set behavior of bottom nav
        preferences.hideBottomBar()
            .asImmediateFlow { setBottomNavBehaviorOnScroll() }
            .launchIn(lifecycleScope)

        binding.bottomNav.setOnNavigationItemSelectedListener { item ->
            val id = item.itemId

            val currentRoot = router.backstack.firstOrNull()
            if (currentRoot?.tag()?.toIntOrNull() != id) {
                when (id) {
                    R.id.nav_library -> setRoot(LibraryController(), id)
                    R.id.nav_updates -> setRoot(UpdatesController(), id)
                    R.id.nav_history -> setRoot(HistoryController(), id)
                    R.id.nav_browse -> setRoot(BrowseController(), id)
                    R.id.nav_more -> setRoot(MoreController(), id)
                }
            } else if (!isHandlingShortcut) {
                when (id) {
                    R.id.nav_library -> {
                        val controller = router.getControllerWithTag(id.toString()) as? LibraryController
                        controller?.showSettingsSheet()
                    }
                    R.id.nav_updates -> {
                        router.pushController(DownloadController().withFadeTransaction())
                    }
                }
            }
            true
        }

        val container: ViewGroup = binding.controllerContainer
        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            // Set start screen
            if (!handleIntentAction(intent)) {
                setSelectedNavItem(startScreenId)
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        router.addChangeListener(
            object : ControllerChangeHandler.ControllerChangeListener {
                override fun onChangeStarted(
                    to: Controller?,
                    from: Controller?,
                    isPush: Boolean,
                    container: ViewGroup,
                    handler: ControllerChangeHandler
                ) {
                    syncActivityViewWithController(to, from)
                }

                override fun onChangeCompleted(
                    to: Controller?,
                    from: Controller?,
                    isPush: Boolean,
                    container: ViewGroup,
                    handler: ControllerChangeHandler
                ) {
                }
            }
        )

        syncActivityViewWithController(router.backstack.lastOrNull()?.controller())

        if (savedInstanceState == null) {
            // Show changelog prompt on update
            if (Migrations.upgrade(preferences) && !BuildConfig.DEBUG) {
                WhatsNewDialogController().showDialog(router)
            }
        }

        preferences.extensionUpdatesCount()
            .asImmediateFlow { setExtensionsBadge() }
            .launchIn(lifecycleScope)

        preferences.downloadedOnly()
            .asImmediateFlow { binding.downloadedOnly.isVisible = it }
            .launchIn(lifecycleScope)

        preferences.incognitoMode()
            .asImmediateFlow { binding.incognitoMode.isVisible = it }
            .launchIn(lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        if (!handleIntentAction(intent)) {
            super.onNewIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        getExtensionUpdates()
    }

    private fun setExtensionsBadge() {
        val updates = preferences.extensionUpdatesCount().get()
        if (updates > 0) {
            binding.bottomNav.getOrCreateBadge(R.id.nav_browse).number = updates
        } else {
            binding.bottomNav.removeBadge(R.id.nav_browse)
        }
    }

    private fun getExtensionUpdates() {
        // Limit checks to once a day at most
        if (Date().time < preferences.lastExtCheck().get() + TimeUnit.DAYS.toMillis(1)) {
            return
        }

        lifecycleScope.launchIO {
            try {
                val pendingUpdates = ExtensionGithubApi().checkForUpdates(this@MainActivity)
                preferences.extensionUpdatesCount().set(pendingUpdates.size)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    private fun handleIntentAction(intent: Intent): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(applicationContext, notificationId, intent.getIntExtra("groupId", 0))
        }

        isHandlingShortcut = true

        when (intent.action) {
            SHORTCUT_LIBRARY -> setSelectedNavItem(R.id.nav_library)
            SHORTCUT_RECENTLY_UPDATED -> setSelectedNavItem(R.id.nav_updates)
            SHORTCUT_RECENTLY_READ -> setSelectedNavItem(R.id.nav_history)
            SHORTCUT_CATALOGUES -> setSelectedNavItem(R.id.nav_browse)
            SHORTCUT_EXTENSIONS -> {
                if (router.backstackSize > 1) {
                    router.popToRoot()
                }
                setSelectedNavItem(R.id.nav_browse)
                router.pushController(BrowseController(true).withFadeTransaction())
            }
            SHORTCUT_MANGA -> {
                val extras = intent.extras ?: return false
                if (router.backstackSize > 1) {
                    router.popToRoot()
                }
                setSelectedNavItem(R.id.nav_library)
                router.pushController(RouterTransaction.with(MangaController(extras)))
            }
            SHORTCUT_DOWNLOADS -> {
                if (router.backstackSize > 1) {
                    router.popToRoot()
                }
                setSelectedNavItem(R.id.nav_more)
                router.pushController(RouterTransaction.with(DownloadController()))
            }
            Intent.ACTION_SEARCH, Intent.ACTION_SEND, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY) ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(GlobalSearchController(query).withFadeTransaction())
                }
            }
            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(GlobalSearchController(query, filter).withFadeTransaction())
                }
            }
            else -> {
                isHandlingShortcut = false
                return false
            }
        }

        isHandlingShortcut = false
        return true
    }

    override fun onDestroy() {
        super.onDestroy()

        // Binding sometimes isn't actually instantiated yet somehow
        binding?.bottomNav.setOnNavigationItemSelectedListener(null)
        binding?.toolbar.setNavigationOnClickListener(null)
    }

    override fun onBackPressed() {
        val backstackSize = router.backstackSize
        if (backstackSize == 1 && router.getControllerWithTag("$startScreenId") == null) {
            // Return to start screen
            setSelectedNavItem(startScreenId)
        } else if (shouldHandleExitConfirmation()) {
            // Exit confirmation (resets after 2 seconds)
            lifecycleScope.launchUI { resetExitConfirmation() }
        } else if (backstackSize == 1 || !router.handleBack()) {
            // Regular back
            super.onBackPressed()
        }
    }

    private suspend fun resetExitConfirmation() {
        isConfirmingExit = true
        val toast = toast(R.string.confirm_exit, Toast.LENGTH_LONG)
        delay(2000)
        toast.cancel()
        isConfirmingExit = false
    }

    private fun shouldHandleExitConfirmation(): Boolean {
        return router.backstackSize == 1 &&
            router.getControllerWithTag("$startScreenId") != null &&
            preferences.confirmExit() &&
            !isConfirmingExit
    }

    fun setSelectedNavItem(itemId: Int) {
        if (!isFinishing) {
            binding.bottomNav.selectedItemId = itemId
        }
    }

    private fun setRoot(controller: Controller, id: Int) {
        router.setRoot(controller.withFadeTransaction().tag(id.toString()))
    }

    private fun syncActivityViewWithController(to: Controller?, from: Controller? = null) {
        if (from is DialogController || to is DialogController) {
            return
        }
        if (from is PreferenceDialogController || to is PreferenceDialogController) {
            return
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(router.backstackSize != 1)

        // Always show appbar again when changing controllers
        binding.appbar.setExpanded(true)

        if ((from == null || from is RootController) && to !is RootController) {
            showBottomNav(visible = false, collapse = true)
        }
        if (to is RootController) {
            // Always show bottom nav again when returning to a RootController
            showBottomNav(visible = true, collapse = from !is RootController)
        }

        if (from is TabbedController) {
            from.cleanupTabs(binding.tabs)
        }
        if (to is TabbedController) {
            tabAnimator.expand()
            to.configureTabs(binding.tabs)
        } else {
            tabAnimator.collapse()
            binding.tabs.setupWithViewPager(null)
        }

        if (from is FabController) {
            binding.rootFab.isVisible = false
            from.cleanupFab(binding.rootFab)
        }
        if (to is FabController) {
            binding.rootFab.isVisible = true
            to.configureFab(binding.rootFab)
        }

        when (to) {
            is NoToolbarElevationController -> {
                binding.appbar.disableElevation()
            }
            is ToolbarLiftOnScrollController -> {
                binding.appbar.enableElevation(true)
            }
            else -> {
                binding.appbar.enableElevation(false)
            }
        }
    }

    fun showBottomNav(visible: Boolean, collapse: Boolean = false) {
        val layoutParams = binding.bottomNav.layoutParams as CoordinatorLayout.LayoutParams
        val bottomViewNavigationBehavior = layoutParams.behavior as? HideBottomViewOnScrollBehavior
        if (visible) {
            if (collapse) {
                bottomNavAnimator.expand()
            }

            bottomViewNavigationBehavior?.slideUp(binding.bottomNav)
        } else {
            if (collapse) {
                bottomNavAnimator.collapse()
            }

            bottomViewNavigationBehavior?.slideDown(binding.bottomNav)
        }
    }

    /**
     * Used to manually offset a view within the activity's child views that might be cut off due to
     * the collapsing AppBarLayout.
     */
    fun fixViewToBottom(view: View) {
        val listener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
            val maxAbsOffset = appBarLayout.measuredHeight - binding.tabs.measuredHeight
            view.translationY = -maxAbsOffset - verticalOffset.toFloat() + appBarLayout.marginTop
        }
        binding.appbar.addOnOffsetChangedListener(listener)
        fixedViewsToBottom[view] = listener
    }

    fun clearFixViewToBottom(view: View) {
        val listener = fixedViewsToBottom.remove(view)
        binding.appbar.removeOnOffsetChangedListener(listener)
    }

    private fun setBottomNavBehaviorOnScroll() {
        showBottomNav(visible = true)

        binding.bottomNav.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            behavior = when {
                preferences.hideBottomBar().get() -> HideBottomViewOnScrollBehavior<View>()
                else -> null
            }
        }
    }

    companion object {
        // Shortcut actions
        const val SHORTCUT_LIBRARY = "eu.kanade.tachiyomi.SHOW_LIBRARY"
        const val SHORTCUT_RECENTLY_UPDATED = "eu.kanade.tachiyomi.SHOW_RECENTLY_UPDATED"
        const val SHORTCUT_RECENTLY_READ = "eu.kanade.tachiyomi.SHOW_RECENTLY_READ"
        const val SHORTCUT_CATALOGUES = "eu.kanade.tachiyomi.SHOW_CATALOGUES"
        const val SHORTCUT_DOWNLOADS = "eu.kanade.tachiyomi.SHOW_DOWNLOADS"
        const val SHORTCUT_MANGA = "eu.kanade.tachiyomi.SHOW_MANGA"
        const val SHORTCUT_EXTENSIONS = "eu.kanade.tachiyomi.EXTENSIONS"

        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
    }
}
