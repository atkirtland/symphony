package io.github.zyrouge.symphony.services.groove.repositories

import android.net.Uri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.utils.ActivityUtils
import io.github.zyrouge.symphony.utils.FuzzySearchOption
import io.github.zyrouge.symphony.utils.FuzzySearcher
import io.github.zyrouge.symphony.utils.KeyGenerator
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.mutate
import io.github.zyrouge.symphony.utils.SimplePath
import io.github.zyrouge.symphony.utils.withCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

class PlaylistRepository(private val symphony: Symphony) {
    enum class SortBy {
        CUSTOM,
        TITLE,
        TRACKS_COUNT,
    }

    private val cache = ConcurrentHashMap<String, Playlist>()
    private val fileWriteMutex = Mutex() // Prevent concurrent writes to the same file
    internal val idGenerator = KeyGenerator.TimeIncremental()
    private val searcher = FuzzySearcher<String>(
        options = listOf(FuzzySearchOption({ v -> get(v)?.title?.let { compareString(it) } }))
    )

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating = _isUpdating.asStateFlow()
    private val _updateId = MutableStateFlow(0L)
    val updateId = _updateId.asStateFlow()
    private val _all = MutableStateFlow<List<String>>(emptyList())
    val all = _all.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()
    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites = _favorites.asStateFlow()

    private fun emitUpdate(value: Boolean) = _isUpdating.update {
        value
    }

    private fun emitUpdateId() = _updateId.update {
        System.currentTimeMillis()
    }

    private fun emitCount() = _count.update {
        cache.size
    }

    suspend fun fetch() {
        emitUpdate(true)
        try {
            val context = symphony.applicationContext
            val playlists = symphony.database.playlists.entries()
            playlists.values.map { x ->
                val playlist = when {
                    x.isLocal -> {
                        ActivityUtils.makePersistableReadableUri(context, x.uri!!)
                        Playlist.parse(symphony, x.id, x.uri)
                    }

                    else -> x
                }
                cache[playlist.id] = playlist
                _all.update {
                    it + playlist.id
                }
                emitUpdateId()
                emitCount()
            }
            if (!cache.containsKey(FAVORITE_PLAYLIST)) {
                add(getFavorites())
            }
        } catch (_: FileNotFoundException) {
        } catch (err: Exception) {
            Logger.error("PlaylistRepository", "fetch failed", err)
        }
        _favorites.update {
            getFavorites().getSongIds(symphony)
        }
        emitUpdateId()
        emitUpdate(false)
    }

    fun reset() {
        emitUpdate(true)
        cache.clear()
        _all.update {
            emptyList()
        }
        emitCount()
        _favorites.update {
            emptyList()
        }
        emitUpdateId()
        emitUpdate(false)
    }

    fun search(playlistIds: List<String>, terms: String, limit: Int = 7) = searcher
        .search(terms, playlistIds, maxLength = limit)

    fun sort(playlistIds: List<String>, by: SortBy, reverse: Boolean): List<String> {
        val sensitive = symphony.settings.caseSensitiveSorting.value
        val sorted = when (by) {
            SortBy.CUSTOM -> {
                val prefix = listOfNotNull(FAVORITE_PLAYLIST)
                val others = playlistIds.toMutableList()
                prefix.forEach { others.remove(it) }
                prefix + others
            }

            SortBy.TITLE -> playlistIds.sortedBy { get(it)?.title?.withCase(sensitive) }
            SortBy.TRACKS_COUNT -> playlistIds.sortedBy { get(it)?.numberOfTracks }
        }
        return if (reverse) sorted.reversed() else sorted
    }

    fun count() = cache.size
    fun ids() = cache.keys.toList()
    fun values() = cache.values.toList()

    fun get(id: String) = cache[id]
    fun get(ids: List<String>) = ids.mapNotNull { get(it) }

    fun getFavorites() = cache[FAVORITE_PLAYLIST]
        ?: create(FAVORITE_PLAYLIST, "Favorites", emptyList())

    fun create(title: String, songIds: List<String>) = create(idGenerator.next(), title, songIds)
    private fun create(id: String, title: String, songIds: List<String>) = Playlist(
        id = id,
        title = title,
        songPaths = songIds.mapNotNull { symphony.groove.song.get(it)?.path },
        uri = null,
        path = null,
    )

    fun add(playlist: Playlist) {
        cache[playlist.id] = playlist
        _all.update {
            it + playlist.id
        }
        emitUpdateId()
        emitCount()
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.insert(playlist)
        }
    }

    fun delete(id: String) {
        Logger.error(
            "PlaylistRepository",
            "cache ${cache.containsKey(id)}"
        )
        cache.remove(id)?.uri?.let {
            runCatching {
                ActivityUtils.makePersistableReadableUri(symphony.applicationContext, it)
            }
        }
        _all.update {
            it - id
        }
        emitUpdateId()
        emitCount()
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.delete(id)
        }
    }

    fun update(id: String, songIds: List<String>) {
        val playlist = get(id) ?: return
        
        val updated = if (playlist.isLocal) {
            // For local playlists, convert full paths to relative paths for m3u files
            val songPaths = songIds.mapNotNull { songId ->
                symphony.groove.song.get(songId)?.path?.let { fullPath ->
                    // Try to find a relative path that works with the playlist's context
                    findRelativePathForPlaylist(fullPath, playlist)
                }
            }
            Playlist(
                id = id,
                title = playlist.title,
                songPaths = songPaths,
                uri = playlist.uri,
                path = playlist.path,
            )
        } else {
            // For in-app playlists, use full paths as before
            Playlist(
                id = id,
                title = playlist.title,
                songPaths = songIds.mapNotNull { symphony.groove.song.get(it)?.path },
                uri = playlist.uri,
                path = playlist.path,
            )
        }
        
        cache[id] = updated
        emitUpdateId()
        emitCount()
        if (id == FAVORITE_PLAYLIST) {
            _favorites.update {
                songIds
            }
        }
        
        // Save to m3u file if it's a local playlist
        if (playlist.isLocal && playlist.uri != null) {
            symphony.groove.coroutineScope.launch {
                try {
                    savePlaylistToUri(updated, playlist.uri)
                } catch (err: Exception) {
                    Logger.error("PlaylistRepository", "failed to save local playlist", err)
                }
            }
        }
        
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.update(updated)
        }
    }

    // NOTE: maybe we shouldn't use groove's coroutine scope?
    fun favorite(songId: String) {
        val favorites = getFavorites()
        val songIds = favorites.getSongIds(symphony)
        if (songIds.contains(songId)) {
            return
        }
        update(favorites.id, songIds.mutate { add(songId) })
    }

    fun unfavorite(songId: String) {
        val favorites = getFavorites()
        val songIds = favorites.getSongIds(symphony)
        if (!songIds.contains(songId)) {
            return
        }
        update(favorites.id, songIds.mutate { remove(songId) })
    }

    fun isFavoritesPlaylist(playlist: Playlist) = playlist.id == FAVORITE_PLAYLIST
    fun isBuiltInPlaylist(playlist: Playlist) = isFavoritesPlaylist(playlist)

    suspend fun savePlaylistToUri(playlist: Playlist, uri: Uri) {
        fileWriteMutex.withLock {
            try {
                // Use atomic write mode to prevent corruption
                val outputStream = symphony.applicationContext.contentResolver.openOutputStream(uri, "wt")
                outputStream?.use { stream ->
                    val content = if (playlist.songPaths.isEmpty()) {
                        // Empty playlist - write empty content to clear the file
                        ""
                    } else {
                        // Ensure each path is properly separated with newlines
                        playlist.songPaths.joinToString("\n")
                    }
                    
                    // Write with explicit UTF-8 encoding to prevent character corruption
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    stream.write(bytes)
                    stream.flush()
                }
            } catch (e: Exception) {
                Logger.error("PlaylistRepository", "Failed to save playlist to URI: ${uri}", e)
                throw e
            }
        }
    }

    fun renamePlaylist(playlist: Playlist, title: String) {
        val renamed = playlist.withTitle(title)
        cache[playlist.id] = renamed
        emitUpdateId()
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.update(renamed)
        }
    }

    internal fun onScanFinish() {
        _favorites.update {
            getFavorites().getSongIds(symphony)
        }
        emitUpdateId()
    }

    /**
     * Find the best relative path for a song file relative to the playlist's location
     */
    private fun findRelativePathForPlaylist(fullPath: String, playlist: Playlist): String {
        // If the playlist has a path context, calculate relative path
        playlist.path?.let { playlistPath ->
            val songPath = SimplePath(fullPath)
            val playlistDir = SimplePath(playlistPath).parent
            
            playlistDir?.let { dir ->
                // Try to find the relative path from playlist directory to song
                val relativePath = findRelativePath(songPath, dir)
                if (relativePath != null) {
                    return relativePath
                }
            }
        }
        
        // Fallback: just use the filename
        return SimplePath(fullPath).name
    }

    /**
     * Find relative path from source directory to target file, if possible
     */
    private fun findRelativePath(target: SimplePath, source: SimplePath): String? {
        // Find common ancestor
        val targetParts = target.parts
        val sourceParts = source.parts
        
        var commonIndex = 0
        while (commonIndex < targetParts.size && commonIndex < sourceParts.size && 
               targetParts[commonIndex] == sourceParts[commonIndex]) {
            commonIndex++
        }
        
        // If no common ancestor, can't create relative path
        if (commonIndex == 0) {
            return null
        }
        
        // Calculate relative path
        val upLevels = sourceParts.size - commonIndex
        val relativeParts = mutableListOf<String>()
        
        // Add ".." for each level we need to go up
        repeat(upLevels) {
            relativeParts.add("..")
        }
        
        // Add the remaining parts from target
        relativeParts.addAll(targetParts.subList(commonIndex, targetParts.size))
        
        return relativeParts.joinToString("/")
    }

    companion object {
        private const val FAVORITE_PLAYLIST = "favorites"
    }
}
