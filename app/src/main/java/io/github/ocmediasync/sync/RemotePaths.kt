package io.github.ocmediasync.sync

/** ローカルの MediaStore パスとリモートパスの対応付けに関する純粋関数群。 */
object RemotePaths {

    /**
     * ユーザー入力のリモートフォルダ (例: "/Screenshots/") をパスセグメントに分解する。
     * ルート ("/") は空リスト。不正な入力は null。
     */
    fun parseRemoteDir(input: String): List<String>? {
        val segments = input.trim().split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." || it.isBlank() || it.contains('\\') }) return null
        return segments
    }

    fun formatRemoteDir(segments: List<String>): String = "/" + segments.joinToString("/")

    /**
     * 同期フォルダ (例: "Pictures/Screenshots/") 配下のファイルの RELATIVE_PATH から、
     * 同期フォルダより下のサブディレクトリ部分をセグメントで返す。
     * 共有ストレージは大文字小文字を区別しないので、比較もそれに合わせる。
     */
    fun subdirSegments(folderRelativePath: String, fileRelativePath: String): List<String> {
        val folder = normalizeRelativePath(folderRelativePath)
        val file = normalizeRelativePath(fileRelativePath)
        require(file.startsWith(folder, ignoreCase = true)) { "$fileRelativePath is not under $folderRelativePath" }
        return file.substring(folder.length).split('/').filter { it.isNotEmpty() }
    }

    /** RELATIVE_PATH を末尾 '/' 付き・先頭 '/' なしの形にそろえる。 */
    fun normalizeRelativePath(path: String): String {
        val trimmed = path.trim('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    /** 2つの RELATIVE_PATH が同一、または一方が他方の配下にあるか。 */
    fun overlaps(a: String, b: String): Boolean {
        val na = normalizeRelativePath(a)
        val nb = normalizeRelativePath(b)
        return na.startsWith(nb, ignoreCase = true) || nb.startsWith(na, ignoreCase = true)
    }

    /** 同名ファイルが存在するときの別名。n=1 は元の名前、n>=2 は "name (n).ext"。 */
    fun conflictName(name: String, n: Int): String {
        if (n <= 1) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        } else {
            "$name ($n)"
        }
    }

    /** SQLite の LIKE ... ESCAPE '\' 用に % と _ をエスケープする。 */
    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
