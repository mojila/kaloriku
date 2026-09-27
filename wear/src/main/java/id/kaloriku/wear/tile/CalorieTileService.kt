package id.kaloriku.wear.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import id.kaloriku.shared.KaloriKu
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.sync.SyncRole
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * A tile showing today's calories at a glance. Reads from the shared local cache
 * so it renders instantly without a network round trip.
 */
class CalorieTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val container = KaloriKu.init(applicationContext, SyncRole.WATCH)
        // One blocking section for both reads: the tile callback runs on the main
        // thread, so it must not be blocked twice.
        val (today, target) = runBlocking {
            val summary = async { container.repository.summary(JakartaTime.todayKey()) }
            val settings = async { container.settingsStore.current() }
            summary.await() to settings.await().dailyTargetKcal
        }
        val progress = if (target <= 0) 0 else (today.totalKcal * 100 / target)

        val root = Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(label("KALORI HARI INI"))
            .addContent(value("${today.totalKcal} kkal"))
            .addContent(label("$progress% dari $target kkal"))
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // Rebuild the tile in the background so "today's total" does not go stale
            // while the tile sits on the carousel (the user can log on the phone).
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MILLIS)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(Layout.Builder().setRoot(root).build())
                            .build(),
                    )
                    .build(),
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
        )

    private fun label(text: String): LayoutElementBuilders.LayoutElement =
        Text.Builder()
            .setText(text)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(12f))
                    .setColor(argb(0xFF9B978E.toInt()))
                    .build(),
            )
            .build()

    private fun value(text: String): LayoutElementBuilders.LayoutElement =
        Text.Builder()
            .setText(text)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(28f))
                    .setColor(argb(0xFF7FC79E.toInt()))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .build(),
            )
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"

        /**
         * How often the system may re-request the tile. Fifteen minutes keeps the
         * glance current without waking the device too often.
         */
        const val FRESHNESS_INTERVAL_MILLIS = 15L * 60 * 1000
    }
}

/**
 * Asks the system to rebuild the calorie tile.
 *
 * Called after a local save/edit/delete so the glance reflects the change at once
 * instead of waiting for the freshness interval. Never throws: a tile that is not on
 * the carousel simply has nothing to update.
 */
fun requestCalorieTileUpdate(context: Context) {
    runCatching { TileService.getUpdater(context).requestUpdate(CalorieTileService::class.java) }
}
