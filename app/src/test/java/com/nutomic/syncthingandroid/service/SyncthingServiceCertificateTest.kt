package com.nutomic.syncthingandroid.service

import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import java.io.File
import java.time.Duration
import kotlin.concurrent.thread
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Replacing and resetting the HTTPS certificate of a running [SyncthingService]: the restart, its
 * verification and the rollback, on [SyncthingServiceLifecycleHarness].
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SyncthingServiceCertificateTest : SyncthingServiceLifecycleHarness() {

    @Test
    fun `resetting the certificate while running restarts with a generated one`() {
        val (_, service) = startService()
        startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        val certFile = Constants.getHttpsCertFile(context)
        val keyFile = Constants.getHttpsKeyFile(context)
        keyFile.writeText("old key")
        // What the restarted Syncthing generates: the certificate the fake serves.
        val generated = certFile.readBytes()
        val results = certResults()

        service.resetHttpsCertificate { result, error -> results += result to error }
        shadowOf(Looper.getMainLooper()).idle()
        awaitBackground { !certFile.exists() && !keyFile.exists() }
        certFile.writeBytes(generated)
        binary.rearm()
        awaitWithClock { results.isNotEmpty() }

        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.SUCCESS to null), results)
        assertEquals(listOf("serve --no-browser", "exited", "serve --no-browser"), binary.runs)
        assertEquals(SyncthingService.State.ACTIVE, service.currentState)
        assertFalse(File(certFile.parentFile, certFile.name + ".bak").exists())
        assertFalse(File(keyFile.parentFile, keyFile.name + ".bak").exists())
    }

    @Test
    fun `a certificate Syncthing replaces with a generated one is rolled back`() {
        val (_, service) = startService()
        startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        val certFile = Constants.getHttpsCertFile(context)
        val keyFile = Constants.getHttpsKeyFile(context)
        keyFile.writeText("old key")
        val oldCert = certFile.readBytes()
        val supplied =
            HeldCertificate.Builder().commonName("supplied").build().certificatePem().toByteArray()
        val results = certResults()

        service.replaceHttpsCertificate(supplied, "new key".toByteArray()) { result, error ->
            results += result to error
        }
        shadowOf(Looper.getMainLooper()).idle()
        awaitBackground { certFile.readBytes().contentEquals(supplied) }
        // Syncthing could not load the pair: it generates its own and serves that.
        certFile.writeBytes(oldCert)
        binary.rearm()
        awaitWithClock { results.isNotEmpty() }

        assertEquals(
            listOf(SyncthingService.HttpsCertReplaceResult.FAILED_CERT_REJECTED to null),
            results,
        )
        assertArrayEquals(oldCert, certFile.readBytes())
        assertEquals("old key", keyFile.readText())
        assertFalse(File(certFile.parentFile, certFile.name + ".bak").exists())
        assertFalse(File(keyFile.parentFile, keyFile.name + ".bak").exists())
        // Stopped to restore the backups, then brought back up.
        assertEquals(
            listOf(
                "serve --no-browser",
                "exited",
                "serve --no-browser",
                "exited",
                "serve --no-browser",
            ),
            binary.runs.take(5),
        )
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
    }

    @Test
    fun `replacing the certificate does not wait on the main thread for Syncthing to exit`() {
        val (_, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        // The certificate the fake serves, so the restarted Syncthing passes verification.
        val certPem = Constants.getHttpsCertFile(context).readBytes()
        val results = mutableListOf<SyncthingService.HttpsCertReplaceResult>()

        val millis = millisTaken {
            service.replaceHttpsCertificate(certPem, "new key".toByteArray()) { result, _ ->
                results += result
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertTrue("took $millis ms", millis < EXIT_MILLIS)
        awaitWithClock { results.isNotEmpty() }
        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.SUCCESS), results)
        assertEquals("new key", Constants.getHttpsKeyFile(context).readText())
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs.take(2))
    }

    @Test
    fun `applying a certificate while Syncthing is stopped reports success on the main thread`() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences
            .edit()
            .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_STOP)
            .commit()
        val (_, service) = startService()
        val results = mutableListOf<SyncthingService.HttpsCertReplaceResult>()
        var onMainThread = false

        service.replaceHttpsCertificate("cert".toByteArray(), "key".toByteArray()) { result, _ ->
            onMainThread = Looper.myLooper() == Looper.getMainLooper()
            results += result
        }

        awaitWithClock { results.isNotEmpty() }
        assertTrue("certificate result delivered off the main thread", onMainThread)
        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.SUCCESS_PENDING_START), results)
    }

    @Test
    fun `replacing the certificate writes the files off the main thread`() {
        val (_, service) = startService()
        startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        // The certificate the fake serves, so the restarted Syncthing passes verification.
        val certPem = Constants.getHttpsCertFile(context).readBytes()
        val results = mutableListOf<SyncthingService.HttpsCertReplaceResult>()
        val writeThreads = mutableListOf<Thread>()
        atomicWriteThreadHook = { writeThreads += it }
        try {
            service.replaceHttpsCertificate(certPem, "new key".toByteArray()) { result, _ ->
                results += result
            }
            awaitWithClock { results.isNotEmpty() }
        } finally {
            atomicWriteThreadHook = null
        }

        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.SUCCESS), results)
        assertTrue("the certificate files were not written", writeThreads.isNotEmpty())
        assertTrue(
            "the certificate files were written on the main thread",
            writeThreads.all { it != Looper.getMainLooper().thread },
        )
    }

    @Test
    fun `a certificate replace deferred while Syncthing starts fails once the service is destroyed`() {
        val (controller, service) = startService()
        awaitWithClock { binary.started }
        assertEquals(SyncthingService.State.STARTING, service.currentState)
        val results = certResults()
        service.replaceHttpsCertificate("cert".toByteArray(), "key".toByteArray()) { result, error
            ->
            results += result to error
        }
        shadowOf(Looper.getMainLooper()).idle()

        controller.destroy()
        startSyncthingWhenLaunched()

        awaitWithClock { results.isNotEmpty() && "exited" in binary.runs }
        // Past the deferral of every retry: nothing may start Syncthing again.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))
        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.FAILED to null), results)
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs)
    }

    @Test
    fun `a certificate reset deferred while Syncthing starts fails once the service is destroyed`() {
        val (controller, service) = startService()
        awaitWithClock { binary.started }
        assertEquals(SyncthingService.State.STARTING, service.currentState)
        val results = certResults()
        service.resetHttpsCertificate { result, error -> results += result to error }
        shadowOf(Looper.getMainLooper()).idle()

        controller.destroy()
        startSyncthingWhenLaunched()

        awaitWithClock { results.isNotEmpty() && "exited" in binary.runs }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))
        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.FAILED to null), results)
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs)
    }

    @Test
    fun `a certificate replace waiting for Syncthing to exit fails once the service is destroyed`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        val certFile = Constants.getHttpsCertFile(context)
        val keyFile = Constants.getHttpsKeyFile(context)
        keyFile.writeText("old key")
        val oldCert = certFile.readBytes()
        val oldKey = keyFile.readBytes()
        val results = certResults()
        service.replaceHttpsCertificate("cert".toByteArray(), "key".toByteArray()) { result, error
            ->
            results += result to error
        }
        shadowOf(Looper.getMainLooper()).idle()

        controller.destroy()

        awaitWithClock { results.isNotEmpty() && "exited" in binary.runs }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))
        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.FAILED to null), results)
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs)
        assertEquals(1, fake.requests("/rest/system/shutdown", "POST").size)
        // The failed change rolled the previous certificate back and left no backups behind.
        assertArrayEquals(oldCert, certFile.readBytes())
        assertArrayEquals(oldKey, keyFile.readBytes())
        assertFalse(File(certFile.parentFile, certFile.name + ".bak").exists())
        assertFalse(File(keyFile.parentFile, keyFile.name + ".bak").exists())
    }

    @Test
    fun `a certificate reset waiting for Syncthing to exit fails once the service is destroyed`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        val certFile = Constants.getHttpsCertFile(context)
        val keyFile = Constants.getHttpsKeyFile(context)
        keyFile.writeText("old key")
        val oldCert = certFile.readBytes()
        val oldKey = keyFile.readBytes()
        val results = certResults()
        service.resetHttpsCertificate { result, error -> results += result to error }
        shadowOf(Looper.getMainLooper()).idle()

        controller.destroy()

        awaitWithClock { results.isNotEmpty() && "exited" in binary.runs }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))
        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.FAILED to null), results)
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs)
        assertEquals(1, fake.requests("/rest/system/shutdown", "POST").size)
        // The failed reset rolled the previous certificate back and left no backups behind.
        assertArrayEquals(oldCert, certFile.readBytes())
        assertArrayEquals(oldKey, keyFile.readBytes())
        assertFalse(File(certFile.parentFile, certFile.name + ".bak").exists())
        assertFalse(File(keyFile.parentFile, keyFile.name + ".bak").exists())
    }

    @Test
    fun `a failed lifecycle job reports failure and restarts Syncthing`() {
        val (_, service) = startService()
        startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        val results = certResults()
        ShadowUtilDeviceShell.throwOnKillProcess = true
        try {
            service.replaceHttpsCertificate("cert".toByteArray(), "key".toByteArray()) {
                result,
                error ->
                results += result to error
            }
            awaitWithClock { results.isNotEmpty() }
        } finally {
            ShadowUtilDeviceShell.throwOnKillProcess = false
        }

        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.FAILED to null), results)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        awaitWithClock { binary.runs.count { it == "serve --no-browser" } >= 2 }
    }

    @Test
    fun `a certificate replace whose restart never leaves STARTING rolls back once`() {
        val (_, service) = startService()
        val fake = startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        val certFile = Constants.getHttpsCertFile(context)
        val keyFile = Constants.getHttpsKeyFile(context)
        keyFile.writeText("old key")
        val oldCert = certFile.readBytes()
        val results = certResults()

        service.replaceHttpsCertificate("new cert".toByteArray(), "new key".toByteArray()) {
            result,
            error ->
            results += result to error
        }
        shadowOf(Looper.getMainLooper()).idle()
        // Syncthing exits off the main thread; the restart waits for the main looper.
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while ("exited" !in binary.runs) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the exit" }
            Thread.sleep(STEP_MILLIS)
        }
        // The restarted Syncthing runs, but its web GUI never comes online.
        fake.close()
        binary.rearm()
        awaitWithClock { binary.runs.size >= 3 }
        assertEquals(SyncthingService.State.STARTING, service.currentState)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HTTPS_CERT_VERIFY_TIMEOUT_MS))
        // What killProcess does to the real binary (shadowed on the host).
        binary.stop()
        val resultDeadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (results.isEmpty() && System.currentTimeMillis() < resultDeadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MILLIS))
            Thread.sleep(STEP_MILLIS)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))

        assertEquals(
            listOf(SyncthingService.HttpsCertReplaceResult.FAILED_NOT_ONLINE to null),
            results,
        )
        assertTrue(oldCert.contentEquals(certFile.readBytes()))
        assertEquals("old key", keyFile.readText())
    }
}
