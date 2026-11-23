package io.github.ocmediasync.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class TusMetadataTest {
    @Test
    fun encodesUtf8ValuesAsBase64() {
        assertEquals(
            "filename 55S75YOPLnBuZw==,mtime MTcwMDAwMDAwMA==",
            TusMetadata.encode(linkedMapOf("filename" to "画像.png", "mtime" to "1700000000")),
        )
    }
}
