package io.github.ocmediasync.media

import android.net.Uri
import android.provider.DocumentsContract

/** 標準のフォルダ選択画面 (ACTION_OPEN_DOCUMENT_TREE) で選ばれたフォルダと MediaStore のパスの対応 */
object StorageTree {
    /** 端末ストレージ (内部ストレージ・SDカード) の DocumentsProvider */
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 選択画面を開いたときの初期位置 (内部ストレージの Pictures) */
    fun initialUri(): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Pictures")

    /** ツリー URI を RELATIVE_PATH (例: "Pictures/Screenshots/") に変換する。端末ストレージ以外は null */
    fun relativePathOf(treeUri: Uri): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        return relativePathFromDocumentId(DocumentsContract.getTreeDocumentId(treeUri))
    }

    /** ドキュメントID ("primary:Pictures/Screenshots" 等) から RELATIVE_PATH へ。ストレージのルートは null */
    fun relativePathFromDocumentId(documentId: String): String? {
        val colon = documentId.indexOf(':')
        if (colon < 0) return null
        val path = documentId.substring(colon + 1).trim('/')
        return if (path.isEmpty()) null else "$path/"
    }
}
