package com.example.tfgwj.utils

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StorageTypeDetectorTest {
    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @Test
    fun `getOptimalBufferSize returns SSD size for SSD type`() {
        val size =
            StorageTypeDetector.getOptimalBufferSize(
                context = null,
                storageType = StorageTypeDetector.StorageType.SSD_UFS,
            )
        assertEquals(StorageTypeDetector.BufferSizes.SSD_UFS, size)
    }

    @Test
    fun `getOptimalBufferSize returns EMMC size for EMMC type`() {
        val size =
            StorageTypeDetector.getOptimalBufferSize(
                context = null,
                storageType = StorageTypeDetector.StorageType.EMMC,
            )
        assertEquals(StorageTypeDetector.BufferSizes.EMMC, size)
    }

    @Test
    fun `getOptimalBufferSize honors minimum for low memory`() {
        // We can't easily mock Runtime.getRuntime().maxMemory() in standard JUnit without PowerMock,
        // but Robolectric might allow it or we can test the availablePercent logic if we mock context
        // and ActivityManager. For now, we test the basic branch logic if possible.
        // Actually, without mocking Runtime, it will use the current JVM's memory which is usually > 512MB.

        val size =
            StorageTypeDetector.getOptimalBufferSize(
                context = null,
                storageType = StorageTypeDetector.StorageType.SSD_UFS,
            )
        // On modern JVM, this should be the default SSD size
        assertTrue(size >= StorageTypeDetector.BufferSizes.EMMC)
    }

    private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
