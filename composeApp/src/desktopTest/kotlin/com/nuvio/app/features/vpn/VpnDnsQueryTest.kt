package com.nuvio.app.features.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DNS wire format the bound resolver sends; regression against the random-fill bug. */
class VpnDnsQueryTest {

    @Test
    fun `query has a random id but an exact question section`() {
        val first = BoundDnsResolver.buildQuery("origin.example.test", 1)!!
        val second = BoundDnsResolver.buildQuery("origin.example.test", 1)!!
        assertNotEquals(first.copyOfRange(0, 2).toList(), second.copyOfRange(0, 2).toList(), "transaction id must vary")

        // flags RD=1, QDCOUNT=1, everything else zeroed
        assertTrue(first[2].toInt() and 0x01 != 0, "RD set")
        assertEquals(0, first[3].toInt())
        assertEquals(0, first[4].toInt())
        assertEquals(1, first[5].toInt())
        for (i in 6..11) assertEquals(0, first[i].toInt(), "header byte $i must be zero")

        // question: origin(6).example(7).test(4) root, TYPE A, CLASS IN
        val expectedQuestion = listOf(
            6, 'o'.code, 'r'.code, 'i'.code, 'g'.code, 'i'.code, 'n'.code,
            7, 'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
            4, 't'.code, 'e'.code, 's'.code, 't'.code,
            0,
            0, 1, // TYPE A
            0, 1, // CLASS IN
        ).map { it.toByte() }
        assertEquals(expectedQuestion, first.drop(12).toList())
    }

    @Test
    fun `aaaa queries carry type 28`() {
        val query = BoundDnsResolver.buildQuery("example.com", 28)!!
        assertEquals(28.toByte(), query[query.size - 3]) // low byte of the 16-bit QTYPE
    }

    @Test
    fun `malformed names are refused`() {
        assertNull(BoundDnsResolver.buildQuery("", 1))
        assertNull(BoundDnsResolver.buildQuery("x".repeat(64) + ".com", 1))
    }
}
