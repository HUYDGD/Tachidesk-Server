package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import mu.KotlinLogging
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.kodein.di.DI
import org.kodein.di.conf.global
import org.kodein.di.instance
import suwayomi.tachidesk.manga.impl.MangaList.proxyThumbnailUrl
import suwayomi.tachidesk.manga.impl.Source.getSource
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.download.DownloadManager.EnqueueInput
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.MissingThumbnailException
import suwayomi.tachidesk.manga.impl.util.network.await
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrNull
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrStub
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.clearCachedImage
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.getImageResponse
import suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
import suwayomi.tachidesk.manga.impl.util.updateMangaDownloadDir
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.dataclass.toGenreList
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger { }

object Manga {
    private fun truncate(
        text: String?,
        maxLength: Int,
    ): String? {
        return if (text?.length ?: 0 > maxLength) {
            text?.take(maxLength - 3) + "..."
        } else {
            text
        }
    }

    suspend fun getManga(
        mangaId: Int,
        onlineFetch: Boolean = false,
    ): MangaDataClass {
        var mangaEntry = transaction { MangaTable.select { MangaTable.id eq mangaId }.first() }

        return if (!onlineFetch && mangaEntry[MangaTable.initialized]) {
            getMangaDataClass(mangaId, mangaEntry)
        } else { // initialize manga
            val sManga = fetchManga(mangaId) ?: return getMangaDataClass(mangaId, mangaEntry)

            mangaEntry = transaction { MangaTable.select { MangaTable.id eq mangaId }.first() }

            MangaDataClass(
                id = mangaId,
                sourceId = mangaEntry[MangaTable.sourceReference].toString(),
                url = mangaEntry[MangaTable.url],
                title = mangaEntry[MangaTable.title],
                thumbnailUrl = proxyThumbnailUrl(mangaId),
                thumbnailUrlLastFetched = mangaEntry[MangaTable.thumbnailUrlLastFetched],
                initialized = true,
                artist = sManga.artist,
                author = sManga.author,
                description = sManga.description,
                genre = sManga.genre.toGenreList(),
                status = MangaStatus.valueOf(sManga.status).name,
                inLibrary = mangaEntry[MangaTable.inLibrary],
                inLibraryAt = mangaEntry[MangaTable.inLibraryAt],
                source = getSource(mangaEntry[MangaTable.sourceReference]),
                meta = getMangaMetaMap(mangaId),
                realUrl = mangaEntry[MangaTable.realUrl],
                lastFetchedAt = mangaEntry[MangaTable.lastFetchedAt],
                chaptersLastFetchedAt = mangaEntry[MangaTable.chaptersLastFetchedAt],
                updateStrategy = UpdateStrategy.valueOf(mangaEntry[MangaTable.updateStrategy]),
                freshData = true,
            )
        }
    }

    suspend fun fetchManga(mangaId: Int): SManga? {
        val mangaEntry = transaction { MangaTable.select { MangaTable.id eq mangaId }.first() }

        val source =
            getCatalogueSourceOrNull(mangaEntry[MangaTable.sourceReference])
                ?: return null
        val sManga =
            SManga.create().apply {
                url = mangaEntry[MangaTable.url]
                title = mangaEntry[MangaTable.title]
            }
        val networkManga = source.getMangaDetails(sManga)
        sManga.copyFrom(networkManga)

        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) {
                if (sManga.title != mangaEntry[MangaTable.title]) {
                    val canUpdateTitle = updateMangaDownloadDir(mangaId, sManga.title)

                    if (canUpdateTitle) {
                        it[MangaTable.title] = sManga.title
                    }
                }
                it[MangaTable.initialized] = true

                it[MangaTable.artist] = sManga.artist
                it[MangaTable.author] = sManga.author
                it[MangaTable.description] = truncate(sManga.description, 4096)
                it[MangaTable.genre] = sManga.genre
                it[MangaTable.status] = sManga.status
                if (!sManga.thumbnail_url.isNullOrEmpty() && sManga.thumbnail_url != mangaEntry[MangaTable.thumbnail_url]) {
                    it[MangaTable.thumbnail_url] = sManga.thumbnail_url
                    it[MangaTable.thumbnailUrlLastFetched] = Instant.now().epochSecond
                    clearThumbnail(mangaId)
                }

                it[MangaTable.realUrl] =
                    runCatching {
                        (source as? HttpSource)?.getMangaUrl(sManga)
                    }.getOrNull()

                it[MangaTable.lastFetchedAt] = Instant.now().epochSecond

                it[MangaTable.updateStrategy] = sManga.update_strategy.name
            }
        }

        return sManga
    }

    suspend fun getMangaFull(
        mangaId: Int,
        onlineFetch: Boolean = false,
    ): MangaDataClass {
        val mangaDaaClass = getManga(mangaId, onlineFetch)

        return transaction {
            val unreadCount =
                ChapterTable
                    .select { (ChapterTable.manga eq mangaId) and (ChapterTable.isRead eq false) }
                    .count()

            val downloadCount =
                ChapterTable
                    .select { (ChapterTable.manga eq mangaId) and (ChapterTable.isDownloaded eq true) }
                    .count()

            val chapterCount =
                ChapterTable
                    .select { (ChapterTable.manga eq mangaId) }
                    .count()

            val lastChapterRead =
                ChapterTable
                    .select { (ChapterTable.manga eq mangaId) }
                    .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                    .firstOrNull { it[ChapterTable.isRead] }

            mangaDaaClass.unreadCount = unreadCount
            mangaDaaClass.downloadCount = downloadCount
            mangaDaaClass.chapterCount = chapterCount
            mangaDaaClass.lastChapterRead = lastChapterRead?.let { ChapterTable.toDataClass(it) }

            mangaDaaClass
        }
    }

    private fun getMangaDataClass(
        mangaId: Int,
        mangaEntry: ResultRow,
    ) = MangaDataClass(
        id = mangaId,
        sourceId = mangaEntry[MangaTable.sourceReference].toString(),
        url = mangaEntry[MangaTable.url],
        title = mangaEntry[MangaTable.title],
        thumbnailUrl = proxyThumbnailUrl(mangaId),
        thumbnailUrlLastFetched = mangaEntry[MangaTable.thumbnailUrlLastFetched],
        initialized = true,
        artist = mangaEntry[MangaTable.artist],
        author = mangaEntry[MangaTable.author],
        description = mangaEntry[MangaTable.description],
        genre = mangaEntry[MangaTable.genre].toGenreList(),
        status = MangaStatus.valueOf(mangaEntry[MangaTable.status]).name,
        inLibrary = mangaEntry[MangaTable.inLibrary],
        inLibraryAt = mangaEntry[MangaTable.inLibraryAt],
        source = getSource(mangaEntry[MangaTable.sourceReference]),
        meta = getMangaMetaMap(mangaId),
        realUrl = mangaEntry[MangaTable.realUrl],
        lastFetchedAt = mangaEntry[MangaTable.lastFetchedAt],
        chaptersLastFetchedAt = mangaEntry[MangaTable.chaptersLastFetchedAt],
        updateStrategy = UpdateStrategy.valueOf(mangaEntry[MangaTable.updateStrategy]),
        freshData = false,
    )

    fun getMangaMetaMap(mangaId: Int): Map<String, String> {
        return transaction {
            MangaMetaTable.select { MangaMetaTable.ref eq mangaId }
                .associate { it[MangaMetaTable.key] to it[MangaMetaTable.value] }
        }
    }

    fun modifyMangaMeta(
        mangaId: Int,
        key: String,
        value: String,
    ) {
        transaction {
            val meta =
                MangaMetaTable.select { (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq key) }
                    .firstOrNull()

            if (meta == null) {
                MangaMetaTable.insert {
                    it[MangaMetaTable.key] = key
                    it[MangaMetaTable.value] = value
                    it[MangaMetaTable.ref] = mangaId
                }
            } else {
                MangaMetaTable.update({ (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq key) }) {
                    it[MangaMetaTable.value] = value
                }
            }
        }
    }

    private val applicationDirs by DI.global.instance<ApplicationDirs>()
    private val network: NetworkHelper by injectLazy()

    suspend fun fetchMangaThumbnail(mangaId: Int): Pair<InputStream, String> {
        val cacheSaveDir = applicationDirs.tempThumbnailCacheRoot
        val fileName = mangaId.toString()

        val mangaEntry = transaction { MangaTable.select { MangaTable.id eq mangaId }.first() }
        val sourceId = mangaEntry[MangaTable.sourceReference]

        return when (val source = getCatalogueSourceOrStub(sourceId)) {
            is HttpSource ->
                getImageResponse(cacheSaveDir, fileName) {
                    val thumbnailUrl =
                        mangaEntry[MangaTable.thumbnail_url]
                            ?: if (!mangaEntry[MangaTable.initialized]) {
                                // initialize then try again
                                getManga(mangaId)
                                transaction {
                                    MangaTable.select { MangaTable.id eq mangaId }.first()
                                }[MangaTable.thumbnail_url]!!
                            } else {
                                // source provides no thumbnail url for this manga
                                throw NullPointerException("No thumbnail found")
                            }

                    source.client.newCall(
                        GET(thumbnailUrl, source.headers),
                    ).await()
                }

            is LocalSource -> {
                val imageFile =
                    mangaEntry[MangaTable.thumbnail_url]?.let {
                        val file = File(it)
                        if (file.exists()) {
                            file
                        } else {
                            null
                        }
                    } ?: throw IOException("Thumbnail does not exist")
                val contentType =
                    ImageUtil.findImageType { imageFile.inputStream() }?.mime
                        ?: "image/jpeg"
                imageFile.inputStream() to contentType
            }

            is StubSource ->
                getImageResponse(cacheSaveDir, fileName) {
                    val thumbnailUrl =
                        mangaEntry[MangaTable.thumbnail_url]
                            ?: throw NullPointerException("No thumbnail found")
                    network.client.newCall(
                        GET(thumbnailUrl),
                    ).await()
                }

            else -> throw IllegalArgumentException("Unknown source")
        }
    }

    suspend fun getMangaThumbnail(mangaId: Int): Pair<InputStream, String> {
        val mangaEntry = transaction { MangaTable.select { MangaTable.id eq mangaId }.first() }

        if (mangaEntry[MangaTable.inLibrary]) {
            return try {
                ThumbnailDownloadHelper.getImage(mangaId)
            } catch (_: MissingThumbnailException) {
                ThumbnailDownloadHelper.download(mangaId)
                ThumbnailDownloadHelper.getImage(mangaId)
            }
        }

        return fetchMangaThumbnail(mangaId)
    }

    private fun clearThumbnail(mangaId: Int) {
        val fileName = mangaId.toString()

        clearCachedImage(applicationDirs.tempThumbnailCacheRoot, fileName)
        clearCachedImage(applicationDirs.thumbnailDownloadsRoot, fileName)
    }

    private val downloadAheadQueue = ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<Int, Boolean>>()
    private var downloadAheadTimer: Timer? = null

    private const val MANGAS_KEY = "mangaIds"
    private const val CHAPTERS_KEY = "chapterIds"

    fun downloadAhead(
        mangaIds: List<Int>,
        latestReadChapterIds: List<Int> = emptyList(),
    ) {
        if (serverConfig.autoDownloadAheadLimit.value == 0) {
            return
        }

        val updateDownloadAheadQueue = { key: String, ids: List<Int> ->
            val idSet = downloadAheadQueue[key] ?: ConcurrentHashMap.newKeySet()
            idSet.addAll(ids)
            downloadAheadQueue[key] = idSet
        }

        updateDownloadAheadQueue(MANGAS_KEY, mangaIds)
        updateDownloadAheadQueue(CHAPTERS_KEY, latestReadChapterIds)

        // handle cases where this function gets called multiple times in quick succession.
        // this could happen in case e.g. multiple chapters get marked as read without batching the operation
        downloadAheadTimer?.cancel()
        downloadAheadTimer =
            Timer().apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            downloadAheadChapters(
                                downloadAheadQueue[MANGAS_KEY]?.toList().orEmpty(),
                                downloadAheadQueue[CHAPTERS_KEY]?.toList().orEmpty(),
                            )
                            downloadAheadQueue.clear()
                        }
                    },
                    5000,
                )
            }
    }

    /**
     * Downloads the latest unread and not downloaded chapters for each passed manga id.
     *
     * To pass a specific chapter as the latest read chapter for a manga, it can be provided in the "latestReadChapterIds" list.
     * This makes it possible to handle cases, where the actual latest read chapter isn't marked as read yet.
     * E.g. the client marks a chapter as read and at the same time sends the "downloadAhead" mutation.
     * In this case, the latest read chapter could potentially be the one, that just got send to get marked as read by the client.
     * Without providing it in "latestReadChapterIds" it could be incorrectly included in the chapters, that will get downloaded.
     *
     * The latest read chapter will be considered the starting point.
     * E.g.:
     * - 20 chapters
     * - chapter 15 marked as read
     * - 16 - 20 marked as unread
     * - 10 - 14 marked as unread
     *
     * will download the unread chapters starting from chapter 15
     */
    private fun downloadAheadChapters(
        mangaIds: List<Int>,
        latestReadChapterIds: List<Int>,
    ) {
        val mangaToLatestReadChapterIndex =
            transaction {
                ChapterTable.select { (ChapterTable.manga inList mangaIds) and (ChapterTable.isRead eq true) }
                    .orderBy(ChapterTable.sourceOrder to SortOrder.DESC).groupBy { it[ChapterTable.manga].value }
            }.mapValues { (_, chapters) -> chapters.firstOrNull()?.let { it[ChapterTable.sourceOrder] } ?: 0 }

        val mangaToUnreadChaptersMap =
            transaction {
                ChapterTable.select { (ChapterTable.manga inList mangaIds) and (ChapterTable.isRead eq false) }
                    .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                    .groupBy { it[ChapterTable.manga].value }
            }

        val chapterIdsToDownload =
            mangaToUnreadChaptersMap.map { (mangaId, unreadChapters) ->
                val latestReadChapterIndex = mangaToLatestReadChapterIndex[mangaId] ?: 0
                val lastChapterToDownloadIndex =
                    unreadChapters.indexOfLast {
                        it[ChapterTable.sourceOrder] > latestReadChapterIndex &&
                            it[ChapterTable.id].value !in latestReadChapterIds
                    }
                val unreadChaptersToConsider = unreadChapters.subList(0, lastChapterToDownloadIndex + 1)
                val firstChapterToDownloadIndex =
                    (unreadChaptersToConsider.size - serverConfig.autoDownloadAheadLimit.value).coerceAtLeast(0)
                unreadChaptersToConsider.subList(firstChapterToDownloadIndex, lastChapterToDownloadIndex + 1)
                    .filter { !it[ChapterTable.isDownloaded] }
                    .map { it[ChapterTable.id].value }
            }.flatten()

        logger.info { "downloadAheadChapters: download chapters [${chapterIdsToDownload.joinToString(", ")}]" }

        DownloadManager.dequeue(mangaIds, chapterIdsToDownload)
        DownloadManager.enqueue(EnqueueInput(chapterIdsToDownload))
    }
}
