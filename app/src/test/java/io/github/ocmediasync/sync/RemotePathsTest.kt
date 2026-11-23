package io.github.ocmediasync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathsTest {
    @Test
    fun parseRemoteDir() {
        assertEquals(listOf("Screenshots"), RemotePaths.parseRemoteDir("/Screenshots/"))
        assertEquals(listOf("a", "b c"), RemotePaths.parseRemoteDir(" a//b c "))
        assertEquals(emptyList<String>(), RemotePaths.parseRemoteDir("/"))
        assertNull(RemotePaths.parseRemoteDir("/a/../b"))
        assertNull(RemotePaths.parseRemoteDir("/a/./b"))
    }

    @Test
    fun formatRemoteDir() {
        assertEquals("/", RemotePaths.formatRemoteDir(emptyList()))
        assertEquals("/a/b", RemotePaths.formatRemoteDir(listOf("a", "b")))
    }

    @Test
    fun subdirSegments() {
        assertEquals(emptyList<String>(), RemotePaths.subdirSegments("Pictures/Screenshots/", "Pictures/Screenshots/"))
        assertEquals(
            listOf("Twitter", "2026"),
            RemotePaths.subdirSegments("Pictures/", "Pictures/Twitter/2026/"),
        )
        // 共有ストレージは大文字小文字を区別しない
        assertEquals(listOf("sub"), RemotePaths.subdirSegments("pictures/screenshots", "Pictures/Screenshots/sub/"))
    }

    @Test
    fun overlaps() {
        assertTrue(RemotePaths.overlaps("Pictures/", "Pictures/Screenshots/"))
        assertTrue(RemotePaths.overlaps("Pictures/Screenshots/", "Pictures/"))
        assertTrue(RemotePaths.overlaps("DCIM/", "dcim"))
        assertFalse(RemotePaths.overlaps("Pictures/Screen/", "Pictures/Screenshots/"))
        assertFalse(RemotePaths.overlaps("Movies/", "Pictures/"))
    }

    @Test
    fun conflictName() {
        assertEquals("a.png", RemotePaths.conflictName("a.png", 1))
        assertEquals("a (2).png", RemotePaths.conflictName("a.png", 2))
        assertEquals("a.tar (3).gz", RemotePaths.conflictName("a.tar.gz", 3))
        assertEquals("noext (2)", RemotePaths.conflictName("noext", 2))
        assertEquals(".hidden (2)", RemotePaths.conflictName(".hidden", 2))
    }

    @Test
    fun escapeLike() {
        assertEquals("Pictures/My\\_Folder\\%/", RemotePaths.escapeLike("Pictures/My_Folder%/"))
        assertEquals("a\\\\b", RemotePaths.escapeLike("a\\b"))
    }
}
