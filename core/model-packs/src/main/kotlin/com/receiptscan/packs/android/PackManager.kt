package com.receiptscan.packs.android

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.receiptscan.packs.HttpsByteSource
import com.receiptscan.packs.PackCatalog
import com.receiptscan.packs.PackCatalogs
import com.receiptscan.packs.PackSpec
import com.receiptscan.packs.PackState
import com.receiptscan.packs.PackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android side of feature packs: a verified [PackStore] under app-private storage, downloads run by
 * WorkManager (they continue when the person leaves the screen), and observable [PackState]s.
 * Packs contain model weights and data only; runtime code ships in the APK.
 */
class PackManager private constructor(private val context: Context) {
    val catalog: PackCatalog = PackCatalogs.parse(context.assets.open("packs/catalog.json").bufferedReader().use { it.readText() })
    private val root = File(context.filesDir, "packs")
    val store = PackStore(root, HttpsByteSource()) { StatFs(context.filesDir.absolutePath).availableBytes }
    private val work = WorkManager.getInstance(context)
    private val refresh = MutableStateFlow(0)

    fun spec(id: String): PackSpec? = catalog.packs.firstOrNull { it.id == id }

    fun file(id: String): File? = spec(id)?.let { store.file(it) }

    fun state(id: String): PackState = spec(id)?.let { store.state(it) } ?: PackState.Unavailable("Unknown pack")

    fun observe(id: String): Flow<PackState> =
        combine(work.getWorkInfosForUniqueWorkFlow(workName(id)), refresh) { infos, _ -> infos.lastOrNull() }.map { info ->
            val spec = spec(id) ?: return@map PackState.Unavailable("Unknown pack")
            when (info?.state) {
                WorkInfo.State.RUNNING -> {
                    val done = info.progress.getLong(KEY_DONE, 0)
                    val total = info.progress.getLong(KEY_TOTAL, spec.sizeBytes)
                    if (done >= total && total > 0) PackState.Verifying else PackState.Downloading(done, total)
                }
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> PackState.Downloading(0, spec.sizeBytes)
                WorkInfo.State.FAILED -> {
                    val reason = info.outputData.getString(KEY_ERROR) ?: "Download failed"
                    if (reason.startsWith(SPACE_PREFIX)) {
                        val parts = reason.removePrefix(SPACE_PREFIX).split(':')
                        PackState.NeedsSpace(parts[0].toLong(), parts[1].toLong())
                    } else PackState.Failed(reason, retryable = true)
                }
                else -> store.state(spec)
            }
        }

    fun download(id: String, wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<PackDownloadWorker>()
            .setInputData(workDataOf(KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, req)
    }

    /** Stops the download and keeps the partial file so it resumes later. */
    fun pause(id: String) { work.cancelUniqueWork(workName(id)); refresh.value++ }

    /** Stops the download and removes everything downloaded so far. */
    fun cancel(id: String) {
        work.cancelUniqueWork(workName(id))
        store.delete(id)
        work.pruneWork()
        refresh.value++
    }

    fun delete(id: String) { store.delete(id); work.pruneWork(); refresh.value++ }

    fun sizeOnDisk(): Long = store.storageReport(catalog.packs).values.sum()

    internal suspend fun runInstall(id: String, progress: suspend (Long, Long) -> Unit, isStopped: () -> Boolean): PackState = withContext(Dispatchers.IO) {
        val spec = spec(id) ?: return@withContext PackState.Unavailable("Unknown pack")
        var last = 0L
        val pending = mutableListOf<Pair<Long, Long>>()
        val result = store.install(spec, onProgress = { d, t -> if (d - last > 256 * 1024 || d == t) { last = d; synchronized(pending) { pending += d to t } } }, isCancelled = isStopped)
        synchronized(pending) { pending.lastOrNull() }?.let { progress(it.first, it.second) }
        refresh.value++
        result
    }

    companion object {
        internal const val KEY_ID = "pack"
        internal const val KEY_DONE = "done"
        internal const val KEY_TOTAL = "total"
        internal const val KEY_ERROR = "error"
        internal const val SPACE_PREFIX = "space:"
        private fun workName(id: String) = "pack-download-$id"

        @Volatile private var instance: PackManager? = null
        fun get(context: Context): PackManager = instance ?: synchronized(this) {
            instance ?: PackManager(context.applicationContext).also { instance = it }
        }
    }
}

class PackDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(PackManager.KEY_ID) ?: return Result.failure()
        val manager = PackManager.get(applicationContext)
        val spec = manager.spec(id) ?: return Result.failure(workDataOf(PackManager.KEY_ERROR to "Unknown pack"))
        setProgress(workDataOf(PackManager.KEY_DONE to 0L, PackManager.KEY_TOTAL to spec.sizeBytes))
        // Report progress from a polling loop so the blocking download is not slowed down.
        val partial = File(applicationContext.filesDir, "packs/${spec.id}/.download-${spec.version}.part")
        val poller = kotlinx.coroutines.coroutineScope {
            val job = launch {
                while (true) {
                    setProgress(workDataOf(PackManager.KEY_DONE to (if (partial.exists()) partial.length() else 0L), PackManager.KEY_TOTAL to spec.sizeBytes))
                    kotlinx.coroutines.delay(500)
                }
            }
            val r = manager.runInstall(id, { _, _ -> }, { isStopped })
            job.cancel()
            r
        }
        return when (poller) {
            is PackState.Ready -> Result.success()
            is PackState.Paused -> Result.retry()
            is PackState.NeedsSpace -> Result.failure(workDataOf(PackManager.KEY_ERROR to "${PackManager.SPACE_PREFIX}${poller.requiredBytes}:${poller.freeBytes}"))
            is PackState.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(PackManager.KEY_ERROR to poller.reason))
            else -> Result.failure(workDataOf(PackManager.KEY_ERROR to poller.toString()))
        }
    }
}
