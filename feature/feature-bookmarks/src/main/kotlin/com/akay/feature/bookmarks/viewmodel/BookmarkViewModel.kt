package com.akay.feature.bookmarks.viewmodel

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.domain.model.Bookmark
import com.akay.core.domain.model.BookmarkFolder
import com.akay.core.domain.repository.BookmarkRepository
import com.akay.feature.bookmarks.format.NetscapeBookmarkFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class BookmarksUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val folders: List<BookmarkFolder> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val importMessage: String? = null
)

@HiltViewModel
class BookmarkViewModel @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookmarksUiState())
    val uiState: StateFlow<BookmarksUiState> = _uiState.asStateFlow()

    init {
        loadBookmarks()
    }

    private fun loadBookmarks() {
        viewModelScope.launch {
            bookmarkRepository.getAllBookmarks().collect { bookmarks ->
                _uiState.value = _uiState.value.copy(bookmarks = bookmarks)
            }
        }
        viewModelScope.launch {
            bookmarkRepository.getAllFolders().collect { folders ->
                _uiState.value = _uiState.value.copy(folders = folders)
            }
        }
    }

    fun addBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookmarkRepository.addBookmark(bookmark)
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(id)
        }
    }

    fun searchBookmarks(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        if (query.isBlank()) {
            loadBookmarks()
        } else {
            viewModelScope.launch {
                bookmarkRepository.searchBookmarks(query).collect { bookmarks ->
                    _uiState.value = _uiState.value.copy(bookmarks = bookmarks)
                }
            }
        }
    }

    /** Writes all bookmarks to a shareable HTML file and returns a content:// Uri for it. */
    suspend fun exportBookmarksToFile(): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val bookmarks = bookmarkRepository.getAllBookmarks().first()
            val html = NetscapeBookmarkFormat.export(bookmarks)
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "axbrowser_bookmarks.html")
            file.writeText(html)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    fun importBookmarksFromHtml(html: String) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) { NetscapeBookmarkFormat.parse(html) }
            imported.forEach { bookmarkRepository.addBookmark(it) }
            _uiState.value = _uiState.value.copy(importMessage = "Imported ${imported.size} bookmarks")
        }
    }

    fun clearImportMessage() {
        _uiState.value = _uiState.value.copy(importMessage = null)
    }
}
