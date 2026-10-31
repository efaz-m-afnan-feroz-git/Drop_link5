package app.droplink.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class FrameCodecTest {

    @Test
    fun roundTripsControlAndDataFrames() {
        val bos = ByteArrayOutputStream()
        val w = FrameWriter(bos)
        w.writeJson(FrameType.HELLO, mapOf("a" to 1L))
        val data = ByteArray(Protocol.BUFFER_SIZE) { (it % 251).toByte() }
        w.write(FrameType.DATA, data)
        w.writeEmpty(FrameType.DONE)

        val r = FrameReader(ByteArrayInputStream(bos.toByteArray()))
        val f1 = r.next()
        assertEquals(FrameType.HELLO, f1.type)
        assertEquals(1L, f1.json()["a"])
        val f2 = r.next()
        assertEquals(FrameType.DATA, f2.type)
        assertEquals(data.size, f2.length)
        assertArrayEquals(data, f2.payload.copyOf(f2.length))
        assertEquals(FrameType.DONE, r.next().type)
        try {
            r.next(); fail("expected EOF")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun headerIsTypeThenBigEndianLength() {
        val bytes = FrameWriter.encode(FrameType.PING, byteArrayOf(9, 8, 7))
        assertArrayEquals(byteArrayOf(12, 0, 0, 0, 3, 9, 8, 7), bytes)
    }

    @Test(expected = ProtocolException::class)
    fun rejectsOversizedDataFrame() {
        val bad = byteArrayOf(FrameType.DATA.code.toByte(), 0x7F, 0, 0, 0)
        FrameReader(ByteArrayInputStream(bad)).next()
    }

    @Test(expected = ProtocolException::class)
    fun rejectsUnknownFrameType() {
        FrameReader(ByteArrayInputStream(byteArrayOf(99, 0, 0, 0, 0))).next()
    }

    @Test
    fun expectTurnsCancelIntoRemoteCancel() {
        val bytes = FrameWriter.encode(FrameType.CANCEL, Json.stringify(Messages.cancel("nope")).toByteArray())
        try {
            FrameReader(ByteArrayInputStream(bytes)).expect(FrameType.ACCEPT)
            fail()
        } catch (e: RemoteCancelException) {
            assertEquals("nope", e.reason)
        }
    }
}

class JsonTest {
    @Test
    fun roundTripsNestedValues() {
        val v = mapOf(
            "s" to "q\"uo\\te\n\u0001 ব্যাংক",
            "n" to 1234567890123L,
            "d" to 1.5,
            "b" to true,
            "z" to null,
            "l" to listOf(1L, "x", false),
        )
        assertEquals(v, Json.parse(Json.stringify(v)))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsTrailingGarbage() {
        Json.parse("{} x")
    }

    @Test(expected = ProtocolException::class)
    fun rejectsDeepNesting() {
        Json.parse("[".repeat(100) + "]".repeat(100))
    }
}

class HeaderParsingTest {
    private fun hello(files: List<FileEntry> = listOf(FileEntry("a/b.txt", 10, "text/plain"))) =
        Hello(Protocol.VERSION, "0123456789abcdef", "Pixel", "dev-1234", TransferMode.LAN, false, files)

    @Test
    fun roundTripsHello() {
        val h = hello(listOf(FileEntry("photos/1.jpg", 5, "image/jpeg"), FileEntry("x.bin", 0, "application/octet-stream")))
        val frame = Frame(FrameType.HELLO, Json.stringify(h.toJson()).toByteArray())
        assertEquals(h, Hello.parse(frame))
        assertEquals(5L, h.totalBytes)
    }

    @Test(expected = IncompatibleVersionException::class)
    fun rejectsOldVersion() {
        Hello.fromJson(hello().toJson() + ("v" to 0L))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsTraversal() {
        Hello.fromJson(hello(listOf(FileEntry("../../etc/passwd", 1, "x"))).toJson())
    }

    @Test(expected = ProtocolException::class)
    fun rejectsNegativeSize() {
        Hello.fromJson(hello(listOf(FileEntry("a", -1, "x"))).toJson())
    }

    @Test(expected = ProtocolException::class)
    fun rejectsDuplicatePaths() {
        Hello.fromJson(hello(listOf(FileEntry("A.txt", 1, "x"), FileEntry("a.txt", 1, "x"))).toJson())
    }

    @Test
    fun sanitizesPaths() {
        assertEquals("dir/file_name_.txt", SafePath.sanitize("dir//file:name?.txt"))
        assertEquals("a/b", SafePath.sanitize("a\\b"))
        assertEquals("x", SafePath.sanitize("x..."))
        try {
            SafePath.sanitize("/abs"); fail()
        } catch (_: ProtocolException) {
        }
        try {
            SafePath.sanitize("a/./b"); fail()
        } catch (_: ProtocolException) {
        }
        assertTrue(SafePath.sanitize("y".repeat(500) + ".jpg").let { it.length <= 120 && it.endsWith(".jpg") })
    }
}

class ChecksumTest {
    @Test
    fun knownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksums.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun incrementalEqualsWhole() {
        val data = ByteArray(3_000_001) { (it * 31).toByte() }
        val d = Checksums.sha256()
        Checksums.update(d, ByteArrayInputStream(data), 1_000_000)
        d.update(data, 1_000_000, data.size - 1_000_000)
        assertEquals(Checksums.sha256Hex(data), Checksums.hex(d.digest()))
    }

    @Test(expected = EOFException::class)
    fun updateFailsWhenStreamIsShort() {
        Checksums.update(Checksums.sha256(), ByteArrayInputStream(ByteArray(10)), 11)
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertTrue(Checksums.matches("ABCD", "abcd"))
        assertFalse(Checksums.matches("abcd", "abce"))
    }
}

class ResumePlannerTest {
    private val hello = Hello(
        Protocol.VERSION, "transfer-0001", "Phone", "dev-1", TransferMode.QR_HOTSPOT, true,
        listOf(FileEntry("a", 100, "x"), FileEntry("b", 200, "x"), FileEntry("c", 0, "x")),
    )
    private val fp = "ab".repeat(32)

    @Test
    fun freshTransferStartsAtZero() {
        val plan = ResumePlanner.plan(hello, null, fp) { null }
        assertEquals(listOf(0L, 0L, 0L), plan.offsets)
        assertFalse(plan.isResume)
        assertEquals(300L, plan.remainingBytes)
    }

    @Test
    fun resumesFromMinOfConfirmedAndActual() {
        val ledger = Ledger.fresh(hello, fp).let {
            it.withFile(0, it.files[0].copy(confirmed = 100, done = true, destination = "A"))
                .withFile(1, it.files[1].copy(confirmed = 150, destination = "B"))
        }
        val lengths = mapOf("A" to 100L, "B" to 120L) // fsync lost the tail of B
        val plan = ResumePlanner.plan(hello, ledger, fp) { lengths[it] }
        assertEquals(listOf(100L, 120L, 0L), plan.offsets)
        assertEquals(listOf(true, false, false), plan.done)
        assertTrue(plan.isResume)
        assertEquals(80L, plan.remainingBytes)
    }

    @Test
    fun confirmedNeverExceedsWhatIsOnDisk() {
        val ledger = Ledger.fresh(hello, fp).let { it.withFile(1, it.files[1].copy(confirmed = 50, destination = "B")) }
        val plan = ResumePlanner.plan(hello, ledger, fp) { 190L } // bytes after the last checkpoint are not trusted
        assertEquals(50L, plan.offsets[1])
    }

    @Test
    fun missingDestinationRestartsFile() {
        val ledger = Ledger.fresh(hello, fp).let { it.withFile(1, it.files[1].copy(confirmed = 150, destination = "gone")) }
        val plan = ResumePlanner.plan(hello, ledger, fp) { null }
        assertEquals(0L, plan.offsets[1])
        assertNull(plan.ledger.files[1].destination)
    }

    @Test
    fun differentSenderKeyOrFilesInvalidatesLedger() {
        val ledger = Ledger.fresh(hello, fp).let { it.withFile(1, it.files[1].copy(confirmed = 150, destination = "B")) }
        assertEquals(0L, ResumePlanner.plan(hello, ledger, "cd".repeat(32)) { 200L }.offsets[1])
        val changed = hello.copy(files = hello.files.toMutableList().also { it[1] = FileEntry("b", 201, "x") })
        assertEquals(0L, ResumePlanner.plan(changed, ledger, fp) { 200L }.offsets[1])
    }

    @Test
    fun ledgerStoreRoundTripsAndPrunes() {
        val dir = createTempDir()
        val store = LedgerStore(dir)
        val l = Ledger.fresh(hello, fp).let { it.withFile(0, it.files[0].copy(confirmed = 10, destination = "content://x/1")) }
        store.save(l)
        assertEquals(l, store.load(hello.transferId))
        assertEquals(1, store.prune(maxAgeMs = 1000, now = l.updatedAt + 5000).size)
        assertNull(store.load(hello.transferId))
        dir.deleteRecursively()
    }

    private fun createTempDir() = kotlin.io.path.createTempDirectory("ledger").toFile()
}

class QrPayloadTest {
    private val p = QrPayload("DIRECT-ab Pixel 8", "p@ss word&=x", QrPayload.Security.WPA2, "192.168.49.1", 47820, "ef".repeat(32), "Rahim's phone")

    @Test
    fun roundTrips() {
        assertEquals(p, QrPayload.parse(p.encode()))
    }

    @Test
    fun rejectsJunk() {
        assertNull(QrPayload.parse("https://example.com"))
        assertNull(QrPayload.parse(p.encode().replace("192.168.49.1", "999.1.1.1")))
        assertNull(QrPayload.parse(p.encode().replace("ef".repeat(32), "zz")))
        assertNull(QrPayload.parse(null))
    }
}

class ModeSelectorTest {
    @Test
    fun lanFirstWhenBothSeeEachOther() {
        assertEquals(
            listOf(ConnectionMode.LAN, ConnectionMode.WIFI_DIRECT, ConnectionMode.QR_HOTSPOT),
            ModeSelector.plan(ModeInputs(peerOnLan = true, peerOnWifiDirect = true, wifiDirectUsable = true)),
        )
    }

    @Test
    fun wifiDirectThenQrWithoutLan() {
        val plan = ModeSelector.plan(ModeInputs(peerOnLan = false, peerOnWifiDirect = true, wifiDirectUsable = true))
        assertEquals(listOf(ConnectionMode.WIFI_DIRECT, ConnectionMode.QR_HOTSPOT), plan)
        assertEquals(ConnectionMode.QR_HOTSPOT, ModeSelector.next(plan, ConnectionMode.WIFI_DIRECT))
        assertNull(ModeSelector.next(plan, ConnectionMode.QR_HOTSPOT))
    }

    @Test
    fun qrOnlyWhenWifiDirectBlocked() {
        assertEquals(
            listOf(ConnectionMode.QR_HOTSPOT),
            ModeSelector.plan(ModeInputs(peerOnLan = false, peerOnWifiDirect = true, wifiDirectUsable = false)),
        )
    }

    @Test
    fun forcedModeIsExclusive() {
        assertEquals(
            listOf(ConnectionMode.WIFI_DIRECT),
            ModeSelector.plan(ModeInputs(forced = ConnectionMode.WIFI_DIRECT, peerOnLan = true, peerOnWifiDirect = false, wifiDirectUsable = true)),
        )
    }

    @Test
    fun receiverDefaultsToQrWithoutWifi() {
        assertTrue(ModeSelector.receiverPrefersQr(connectedToWifi = false))
        assertFalse(ModeSelector.receiverPrefersQr(connectedToWifi = true))
    }
}

class SpeedMeterTest {
    @Test
    fun smoothsAndEstimates() {
        val m = SpeedMeter()
        m.update(0, 0)
        for (t in 1..20) m.update(t * 1_000_000L, t * 100L * 5) // 2 MB/s
        assertEquals(2_000_000.0, m.bytesPerSecond, 50_000.0)
        assertEquals(5L, m.etaSeconds(10_000_000))
        assertEquals(2_000_000.0, m.averageBytesPerSecond(20_000_000, 10_000), 1.0)
    }
}

class DirectModeSelectorTest {
    @Test
    fun directNetworkIsTriedBeforeTheRouter() {
        val plan = ModeSelector.plan(ModeInputs(peerOnLan = true, peerOnWifiDirect = true, wifiDirectUsable = true, peerHasDirectNetwork = true))
        assertEquals(listOf(ConnectionMode.WIFI_DIRECT, ConnectionMode.LAN, ConnectionMode.QR_HOTSPOT), plan)
    }

    @Test
    fun forcedLanSkipsDirect() {
        val plan = ModeSelector.plan(ModeInputs(forced = ConnectionMode.LAN, peerOnLan = true, peerOnWifiDirect = true, wifiDirectUsable = true, peerHasDirectNetwork = true))
        assertEquals(listOf(ConnectionMode.LAN), plan)
    }
}

class UsbModeSelectorTest {
    @Test
    fun cableBeatsEverything() {
        val plan = ModeSelector.plan(ModeInputs(peerOnLan = true, peerOnWifiDirect = true, wifiDirectUsable = true, peerHasDirectNetwork = true, peerOnUsb = true))
        assertEquals(ConnectionMode.USB, plan.first())
        assertEquals(ConnectionMode.QR_HOTSPOT, plan.last())
    }
}
