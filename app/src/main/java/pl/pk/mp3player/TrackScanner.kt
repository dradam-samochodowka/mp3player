package pl.pk.mp3player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/** Pojedynczy utwor wykryty w wybranym drzewie katalogow. */
data class Track(
    val uri: Uri,
    val title: String,
    val relativePath: String
)

/**
 * Rekurencyjne skanowanie drzewa katalogow udostepnionego przez Storage Access
 * Framework (ACTION_OPEN_DOCUMENT_TREE). Nie wymaga uprawnienia do calej pamieci
 * masowej - dziala takze na karcie SD i w Androidzie 11+ (scoped storage).
 */
object TrackScanner {

    private const val TAG = "TrackScanner"
    private const val MAX_DEPTH = 12

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "mp4", "3gp", "mka"
    )

    fun scan(context: Context, treeUri: Uri): List<Track> {
        val result = ArrayList<Track>()
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "Niepoprawne treeUri: $treeUri", e)
            return result
        }
        walk(context, treeUri, rootDocId, "", result, 0)
        result.sortWith(
            compareBy(
                { it.relativePath.lowercase() },
                { it.title.lowercase() }
            )
        )
        return result
    }

    private fun walk(
        context: Context,
        treeUri: Uri,
        documentId: String,
        relativePath: String,
        out: MutableList<Track>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Brak uprawnien do $childrenUri", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Blad odczytu $childrenUri", e)
            null
        } ?: return

        cursor.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
                    walk(context, treeUri, docId, childPath, out, depth + 1)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isAudio = ext in AUDIO_EXTENSIONS || mime.startsWith("audio/")
                    if (isAudio) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        out.add(
                            Track(
                                uri = docUri,
                                title = name.substringBeforeLast('.', name),
                                relativePath = relativePath
                            )
                        )
                    }
                }
            }
        }
    }
}
