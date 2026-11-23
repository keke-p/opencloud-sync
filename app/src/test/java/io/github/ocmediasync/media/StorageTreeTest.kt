package io.github.ocmediasync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageTreeTest {
    @Test
    fun convertsDocumentIdToRelativePath() {
        assertEquals("Pictures/Screenshots/", StorageTree.relativePathFromDocumentId("primary:Pictures/Screenshots"))
        assertEquals("DCIM/", StorageTree.relativePathFromDocumentId("1234-ABCD:DCIM/"))
    }

    @Test
    fun rejectsStorageRootAndInvalidIds() {
        assertNull(StorageTree.relativePathFromDocumentId("primary:"))
        assertNull(StorageTree.relativePathFromDocumentId("raw"))
    }
}
