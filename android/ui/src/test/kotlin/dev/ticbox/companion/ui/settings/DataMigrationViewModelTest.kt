package dev.ticbox.companion.ui.settings

import android.net.Uri
import dev.ticbox.companion.api.DataMigration
import dev.ticbox.companion.api.MigrationException
import dev.ticbox.companion.api.MigrationManifest
import dev.ticbox.companion.api.MigrationSection
import dev.ticbox.companion.ui.common.UserMessages
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataMigrationViewModelTest {
    private class SealedFile : DataMigration {
        val manifest =
            MigrationManifest(
                1,
                1,
                "4.0.1",
                0,
                setOf(MigrationSection.HEALTH, MigrationSection.SETTINGS, MigrationSection.PAIRING),
                encrypted = true,
            )
        var given: CharArray? = null
        var password: String? = null
        var imported: Set<MigrationSection>? = null

        override suspend fun export(
            output: OutputStream,
            sections: Set<MigrationSection>,
            password: CharArray?,
        ) = Unit

        override suspend fun inspect(open: () -> InputStream, password: CharArray?) =
            when (password?.concatToString()) {
                null -> throw MigrationException("migration_password_required")
                "secret" -> manifest
                else -> throw MigrationException("migration_wrong_password")
            }

        override suspend fun import(
            open: () -> InputStream,
            password: CharArray?,
            sections: Set<MigrationSection>,
        ) {
            given = password
            this.password = password?.concatToString()
            imported = sections
        }
    }

    @Test
    fun anEncryptedFileAsksForItsPasswordAndImportsOnlyWhatIsChosen() = runTest {
        val file = SealedFile()
        val viewModel =
            DataMigrationViewModel(
                file,
                RuntimeEnvironment.getApplication().contentResolver,
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                UserMessages(),
            )
        val uri = Uri.parse("content://files/backup")
        viewModel.choose(uri)
        assertEquals(ImportStep.Locked(uri), viewModel.importStep.value)
        viewModel.setImportPassword("wrong")
        viewModel.unlock()
        assertEquals(ImportStep.Locked(uri, wrongPassword = true), viewModel.importStep.value)
        viewModel.setImportPassword("secret")
        viewModel.unlock()
        val ready = viewModel.importStep.value as ImportStep.Ready
        assertEquals(file.manifest.sections, ready.chosen)

        viewModel.setImport(MigrationSection.PAIRING, false)
        viewModel.import()
        assertEquals("secret", file.password)
        assertTrue(file.given!!.all { it == ' ' }, "The password is wiped once used")
        assertEquals(setOf(MigrationSection.HEALTH, MigrationSection.SETTINGS), file.imported)
        viewModel.cancelImport()
        assertNull(viewModel.importStep.value)
        assertEquals("", viewModel.importPassword.value)
    }
}
