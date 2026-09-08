package dev.ene.companion

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class StoragePolicyTest {
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File(path))

    @Test fun cleartextIsDeniedWithoutDomainDebugOrManifestOverrides() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS("http://schemas.android.com/apk/res/android", "usesCleartextTraffic"))
        assertEquals("@xml/network_security_config", app.getAttributeNS("http://schemas.android.com/apk/res/android", "networkSecurityConfig"))
        val network = xml("src/main/res/xml/network_security_config.xml")
        assertEquals("network-security-config", network.documentElement.tagName)
        assertEquals(1, network.getElementsByTagName("base-config").length)
        assertEquals("false", (network.getElementsByTagName("base-config").item(0) as Element).getAttribute("cleartextTrafficPermitted"))
        for (tag in listOf("domain-config", "debug-overrides", "trust-anchors")) assertEquals(0, network.getElementsByTagName(tag).length)
        File("src").walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" && it.path != File("src/main/AndroidManifest.xml").path }.forEach {
            val overrides = xml(it.path).getElementsByTagName("application")
            for (index in 0 until overrides.length) {
                val override = overrides.item(index) as Element
                assertFalse(override.hasAttributeNS("http://schemas.android.com/apk/res/android", "usesCleartextTraffic"))
                assertFalse(override.hasAttributeNS("http://schemas.android.com/apk/res/android", "networkSecurityConfig"))
            }
        }
    }
    @Test fun manifestDisablesBackupAndDeclaresLanAndCameraPolicy() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(manifest.contains("android.permission.CAMERA"))
    }

    @Test fun bothBackupPoliciesExcludeAllAppStorageDomains() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val file = File("src/main/res/xml/$name")
            assertTrue("정책 리소스가 필요함: $name", file.isFile)
            val raw = file.readText()
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue(raw.contains("domain=\"$domain\" path=\".\""))
            }
        }
    }
}
