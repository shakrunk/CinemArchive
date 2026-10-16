import org.junit.Test
import org.gradle.api.GradleException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import static org.junit.Assert.*

class GenerateLicenseNoticesTest {
    private static byte[] archive(Map<String, byte[]> files) {
        def bytes = new ByteArrayOutputStream()
        new ZipOutputStream(bytes).withCloseable { zip ->
            files.each { name, content ->
                zip.putNextEntry(new ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    @Test void preservesNestedNoticesAndUtf8ByteOffsets() {
        byte[] text = 'éLicense text'.getBytes('UTF-8')
        def bytes = archive([
            'classes.jar': archive(['META-INF/NOTICE.txt': 'Copyright example'.bytes]),
            'third_party_licenses.json': '{"Embedded":{"start":2,"length":12}}'.bytes,
            'third_party_licenses.txt': text,
        ])
        def notices = GenerateLicenseNotices.extractNotices(bytes, 'example:library:1')
        assertEquals(['Copyright example', 'License text'], notices.collect { it.body })
    }

    @Test(expected = GradleException) void rejectsTruncatedEmbeddedNotice() {
        GenerateLicenseNotices.extractNotices(archive([
            'third_party_licenses.json': '{"Embedded":{"start":0,"length":99}}'.bytes,
            'third_party_licenses.txt': 'short'.bytes,
        ]), 'example:library:1')
    }

    @Test void doesNotAcceptAnUnrelatedLicenseUrl() {
        assertTrue(GenerateLicenseNotices.isApache('http://www.apache.org/licenses/LICENSE-2.0.txt'))
        assertFalse(GenerateLicenseNotices.isApache('https://example.com/LICENSE-2.0.txt'))
        assertFalse(GenerateLicenseNotices.isGoogleTerms('https://example.com/terms'))
    }
}
