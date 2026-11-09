package com.intempt.core

import com.intempt.core.types.StorageKeys
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * INT-3927 / INT-3889 — the SDK keeps profileId/sessionId in plain SharedPreferences, so it must
 * ship backup rules that exclude every prefs file StorageManagerService writes. Pure JVM: parses
 * the shipped XML and checks it against the real StorageKeys, so a new prefs file with no
 * exclusion fails here.
 */
class BackupRulesTest {
    private val prefs =
        listOf(
            StorageKeys.UserPrefs,
            StorageKeys.SessionPrefs,
            StorageKeys.AppPrefs,
            StorageKeys.FragmentPrefs,
        ).map { "${it.key}.xml" }

    private fun rules(name: String): String {
        val f = File("src/main/res/xml/$name")
        assertTrue("$name must ship in the library (looked at ${f.absolutePath})", f.isFile)
        return f.readText()
    }

    private fun assertExcludesAll(
        xml: String,
        sections: List<String>,
    ) {
        for (section in sections) {
            val body = xml.substringAfter("<$section>", "").substringBefore("</$section>")
            assertTrue("<$section> missing", body.isNotEmpty())
            for (p in prefs) {
                assertTrue("<$section> must exclude $p", body.contains("""<exclude domain="sharedpref" path="$p" />"""))
            }
        }
    }

    @Test
    fun `full backup rules exclude every identity prefs file`() =
        assertExcludesAll(rules("intempt_backup_rules.xml"), listOf("full-backup-content"))

    @Test
    fun `data extraction rules exclude every identity prefs file for backup and transfer`() =
        assertExcludesAll(rules("intempt_data_extraction_rules.xml"), listOf("cloud-backup", "device-transfer"))
}
