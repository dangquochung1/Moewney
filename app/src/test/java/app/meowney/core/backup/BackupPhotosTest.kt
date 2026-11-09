package app.meowney.core.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.meowney.container
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupPhotosTest {
    @Test fun backupCarriesNotebookPhotos() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val c = ctx.container
        c.noteImages.file("abcd-1.png").writeText("pixel")
        c.noteImages.file("not a photo.txt").writeText("x")
        val out = File(ctx.cacheDir, "backup.zip")
        BackupManager(ctx, c).export(Uri.fromFile(out))
        val names = ZipFile(out).use { z -> z.entries().toList().map { it.name } }
        assertThat(names).containsAtLeast("manifest.json", "meowney.db", "notes/abcd-1.png")
        assertThat(names).doesNotContain("notes/not a photo.txt")
        c.db.close()
    }
}
