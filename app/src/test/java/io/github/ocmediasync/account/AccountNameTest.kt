package io.github.ocmediasync.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountNameTest {
    @Test
    fun parsesHostOnly() {
        assertEquals(AccountName("alice", "https://cloud.example.com"), AccountName.parse("alice@cloud.example.com"))
    }

    @Test
    fun parsesPortAndPath() {
        assertEquals(
            AccountName("bob", "https://example.com:9200/opencloud"),
            AccountName.parse("bob@example.com:9200/opencloud/"),
        )
    }

    @Test
    fun usernameMayContainAt() {
        assertEquals(
            AccountName("carol@example.org", "https://cloud.example.com"),
            AccountName.parse("carol@example.org@cloud.example.com"),
        )
    }

    @Test
    fun rejectsInvalid() {
        assertNull(AccountName.parse("no-at-sign"))
        assertNull(AccountName.parse("@host"))
        assertNull(AccountName.parse("user@"))
    }
}
