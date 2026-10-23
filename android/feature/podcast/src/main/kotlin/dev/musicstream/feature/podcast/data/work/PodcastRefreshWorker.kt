package dev.musicstream.feature.podcast.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.musicstream.feature.podcast.domain.usecase.RefreshPodcastsUseCase
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Periodically fetches new episodes of subscribed podcasts. */
@HiltWorker
class PodcastRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refresh: RefreshPodcastsUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        refresh().first().fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
        )

    companion object {
        private const val NAME = "podcast-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PodcastRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
