package dev.uednd.hydros

import dev.uednd.hydros.data.network.ApiResponse
import dev.uednd.hydros.data.network.CampusTransport
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.RealWalletRepository
import dev.uednd.hydros.data.repository.parseBill
import dev.uednd.hydros.ui.state.BillType
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletRepositoryTest {
    private val auth = Credentials("test-token", "1", "2", "3", "13800138000")

    private class RecordingTransport(private val responseJson: String) : CampusTransport {
        val requestedPaths = mutableListOf<String>()
        val requestedFields = mutableListOf<Map<String, String>>()

        override suspend fun get(path: String, fields: Map<String, String>, auth: Credentials?): ApiResponse<JsonElement> {
            requestedPaths += path
            requestedFields += fields
            return ApiResponse(success = true, errorCode = 0, data = Json.parseToJsonElement(responseJson))
        }

        override suspend fun post(path: String, fields: Map<String, String>, auth: Credentials?): ApiResponse<JsonElement> {
            requestedPaths += path
            return ApiResponse(success = true, errorCode = 0, data = Json.parseToJsonElement(responseJson))
        }
    }

    @Test
    fun billsQueriesConsumptionTypeAndParsesConsumeBillDto() = runTest {
        val transport = RecordingTransport(
            """[{"consumeBillDTO":{"orderId":"7","consumeDate":"2026-10-15 12:30:00","consumeMoney":"0.50","description":"热水器:宿舍"}}]"""
        )
        val bills = RealWalletRepository(transport).bills(auth, "2026-10", BillType.CONSUMPTION)

        assertEquals("2", transport.requestedFields.single()["billRequestType"])
        assertEquals("2026-10", transport.requestedFields.single()["month"])
        assertEquals(1, bills.size)
        assertEquals(false, bills.single().recharge)
        assertEquals(true, bills.single().detailAvailable)
        assertEquals(500L, bills.single().amountMilli)
    }

    @Test
    fun billsQueriesRechargeTypeAndParsesRechargeBillDtoWithoutDetailId() = runTest {
        val transport = RecordingTransport(
            """[{"rechargeBillDTO":{"dealDate":"2026-10-15 12:30:00","dealMoney":"50.00","typeName":"充值"}}]"""
        )
        val bills = RealWalletRepository(transport).bills(auth, "2026-10", BillType.RECHARGE)

        assertEquals("1", transport.requestedFields.single()["billRequestType"])
        assertEquals(1, bills.size)
        assertEquals(true, bills.single().recharge)
        assertEquals(false, bills.single().detailAvailable)
        assertEquals("充值", bills.single().title)
        assertEquals(50_000L, bills.single().amountMilli)
    }

    @Test
    fun loadWalletOnlyQueriesAccountWalletAndParsesTotalBalance() = runTest {
        val transport = RecordingTransport("""{"money":"12.34","accountRealMoney":"10.00","accountGivenMoney":"2.34"}""")
        val repository = RealWalletRepository(transport)

        val wallet = repository.load(auth)

        // Only account/wallet must be requested, no campus card or account info calls
        assertEquals(listOf("account/wallet"), transport.requestedPaths)
        assertEquals(12_340L, wallet.totalMilli)
        assertEquals(1234L, wallet.totalCents)
    }

    @Test
    fun loadWalletWithMissingMoneyReturnsNullTotalMilli() = runTest {
        val transport = RecordingTransport("""{}""")
        val repository = RealWalletRepository(transport)

        val wallet = repository.load(auth)

        assertEquals(listOf("account/wallet"), transport.requestedPaths)
        assertNull(wallet.totalMilli)
        assertNull(wallet.totalCents)
    }

    @Test
    fun detailUsesServerDisplayDurationAndRetainsListMetadata() = runTest {
        val bill = parseBill(Json.parseToJsonElement(
            """{"orderId":"7","consumeDate":"2026-10-15 12:30:00","description":"消费:宿舍热水","consumeMoney":"0.50"}"""
        ) as JsonObject)
        val repository = RealWalletRepository(RecordingTransport(
            """{"orderId":"7","consumeMoney":"0.50","usingTimeDisplay":"3分20秒"}"""
        ))
        val detail = repository.detail(auth, bill)
        assertEquals("3分20秒", detail.durationText)
        assertEquals(bill.title, detail.title)
        assertEquals(bill.time, detail.time)
    }

    @Test
    fun missingNullAndBlankServerDurationsStayAbsent() = runTest {
        for (field in listOf("", ",\"usingTimeDisplay\":null", ",\"usingTimeDisplay\":\" \"")) {
            val bill = parseBill(Json.parseToJsonElement(
                """{"orderId":"7","consumeDate":"2026-10-15 12:30:00"$field}"""
            ) as JsonObject)
            assertNull(bill.durationText)
        }
    }

    @Test
    fun detailRetainsAmountMilliAndLocationWhenDetailOmitted() = runTest {
        val bill = parseBill(Json.parseToJsonElement(
            """{"orderId":"7","consumeDate":"2026-10-15 12:30:00","description":"消费:宿舍热水","consumeMoney":"0.50"}"""
        ) as JsonObject)
        val repository = RealWalletRepository(RecordingTransport(
            """{"orderId":"7","usingTimeDisplay":"3分20秒"}"""
        ))
        val detail = repository.detail(auth, bill)
        assertEquals(500L, detail.amountMilli)
        assertEquals("宿舍热水", detail.title)
        assertEquals("宿舍热水", detail.location)
        assertEquals("3分20秒", detail.durationText)
    }
}
