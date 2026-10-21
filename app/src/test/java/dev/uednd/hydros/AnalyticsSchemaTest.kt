package dev.uednd.hydros

import dev.uednd.hydros.data.analytics.*
import dev.uednd.hydros.data.storage.FontSizeOption
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsSchemaTest {
    @Test fun scanCountsUseOnlyCoarseBuckets() {
        assertEquals("0", countBucket(0))
        assertEquals("1_2", countBucket(2))
        assertEquals("3_5", countBucket(5))
        assertEquals("6_10", countBucket(10))
        assertEquals("11_plus", countBucket(1000))
    }

    @Test fun resultAndDiagnosticDimensionsAreClosedEnums() {
        assertEquals(setOf("SUCCESS", "PENDING", "FAILURE"), ResultCode.entries.map { it.name }.toSet())
        assertEquals(setOf("NETWORK", "REJECTED", "PERMISSION", "UNKNOWN"), FailureCategory.entries.map { it.name }.toSet())
        assertEquals(setOf("SHOWER", "DRINK", "WASH"), DeviceType.entries.map { it.name }.toSet())
        assertEquals(setOf("LOGIN", "ACCOUNT", "WALLET", "BILL", "DEVICE", "SESSION", "UPDATE"), NetworkOperation.entries.map { it.name }.toSet())
        assertEquals(setOf("PASSWORD", "SMS"), LoginMethod.entries.map { it.name }.toSet())
        assertEquals(setOf("LOGOUT", "EXPIRED", "ACCOUNT_CHANGED"), AuthEndReason.entries.map { it.name }.toSet())
        assertEquals(setOf("LIST", "NEARBY", "QR"), ConnectEntry.entries.map { it.name }.toSet())
        assertEquals(setOf("LOGIN", "SCAN", "DEVICE", "SESSION", "WALLET", "BILL_DETAIL", "ABOUT_UPDATE"), AppScreen.entries.map { it.name }.toSet())
    }

    @Test fun fontScaleBucketsMatchOptionsAndTolerateFloatNoise() {
        FontSizeOption.entries.forEach { assertEquals(it.name, fontScaleBucket(it.scale)) }
        assertEquals(FontSizeOption.LARGE.name, fontScaleBucket(1.1500001f))
        assertEquals("OTHER", fontScaleBucket(1.2f))
        assertEquals("OTHER", fontScaleBucket(2f))
    }
}
