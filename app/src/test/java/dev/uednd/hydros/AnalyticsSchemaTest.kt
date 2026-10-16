package dev.uednd.hydros

import dev.uednd.hydros.data.analytics.*
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
    }
}
