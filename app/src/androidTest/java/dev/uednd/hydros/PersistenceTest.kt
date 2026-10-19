package dev.uednd.hydros

import androidx.test.platform.app.InstrumentationRegistry
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.storage.*
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersistenceTest {
    @Test fun credentialsUseKeystoreAndExpiredAccountKeepsRecoveryRecord()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first=PreferencesHydrosStore(context)
        val auth=Credentials("private-test-token-never-plaintext","11","22","33","13800138000")
        val device=DeviceUi("test-device","测试水表","测试宿舍",DeviceCategory.SHOWER,channel=ControlChannel.BLE)
        try {
            first.credentials(auth)
            first.theme("DARK")
            first.updateAccount(auth.key) { SavedAccount(listOf(device), setOf(device.id), listOf(device.id),
                SessionUi(device,auth.key,stage=SessionStage.WRITEBACK_PENDING,uploaded=true,rateAttempted=true,writeBackHex="0011"), deviceRemarks=mapOf(device.id to "宿舍花洒")) }
            val restarted=PreferencesHydrosStore(context)
            assertEquals(auth,restarted.credentials()); assertEquals("DARK",restarted.theme())
            assertEquals(setOf(device.id),restarted.account(auth.key).favorites)
            assertTrue(restarted.account(auth.key).session?.uploaded==true)
            assertEquals("宿舍花洒",restarted.account(auth.key).deviceRemarks[device.id])
            assertTrue(restarted.account("other-account").deviceRemarks.isEmpty())
            val disk=File(context.filesDir,"datastore/hydros.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains(auth.loginCode)); assertFalse(disk.contains(auth.phone))
            restarted.credentials(null)
            assertNull(first.credentials()); assertEquals(auth.key,first.lastAccount())
            assertNotNull(first.account(auth.key).session)
            assertTrue(first.account("other-account").devices.isEmpty())
            val cipher=CredentialCipher()
            val a=cipher.encrypt("secret"); val b=cipher.encrypt("secret")
            assertNotEquals(a,b); assertEquals("secret",CredentialCipher().decrypt(a))
            assertTrue(runCatching { cipher.decrypt(a.dropLast(4)+"AAAA") }.isFailure)
        } finally {
            first.credentials(null); first.theme("SYSTEM"); first.updateAccount(auth.key) { SavedAccount() }
        }
    }
    @Test fun postSessionDestinationSurvivesStoreRestartAndResetsToDevice()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val writer=PreferencesHydrosStore(context)
        try {
            writer.postSessionDestination(PostSessionDestination.BILL)
            // A fresh facade reads the same DataStore entry, as the app does after a process restart.
            assertEquals(PostSessionDestination.BILL,PreferencesHydrosStore(context).postSessionDestination())
            assertEquals(PostSessionDestination.BILL,writer.postSessionDestination())
        } finally {
            writer.postSessionDestination(PostSessionDestination.DEVICE)
        }
        assertEquals(PostSessionDestination.DEVICE,PreferencesHydrosStore(context).postSessionDestination())
    }
    @Test fun concurrentStoreFacadesDoNotLoseAccountUpdates()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val a=PreferencesHydrosStore(context); val b=PreferencesHydrosStore(context); val key="test-concurrency"
        try {
            a.updateAccount(key) { SavedAccount() }
            coroutineScope { repeat(20) { index -> launch(Dispatchers.IO) {
                (if(index%2==0) a else b).updateAccount(key) { it.copy(favorites=it.favorites+index.toString()) }
            } } }
            assertEquals(20,a.account(key).favorites.size)
        } finally { a.updateAccount(key) { SavedAccount() } }
    }
}
