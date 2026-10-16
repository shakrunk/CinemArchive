import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*

import java.util.zip.ZipInputStream

/** Offline, variant-specific inventory. Unknown or missing license metadata fails the build. */
@CacheableTask
abstract class GenerateLicenseNotices extends DefaultTask {
    @Internal abstract MapProperty<String, File> getLibraries()
    @Internal abstract MapProperty<String, File> getPoms()
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getLicenseDirectory()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @Input List<String> getCoordinates() { libraries.get().keySet().sort() }
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    Collection<File> getLibraryFiles() { libraries.get().values() }
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    Collection<File> getPomFiles() { poms.get().values() }

    // These published POMs inherit or omit their Apache license. Keep exceptions exact.
    static final Set<String> APACHE_OVERRIDES = [
        'javax.inject:javax.inject:1', 'com.google.guava:listenablefuture:1.0',
        'com.google.auto.value:auto-value-annotations:1.6.3', 'com.google.zxing:core:3.5.3',
    ] as Set

    @TaskAction void generate() {
        def entries = []
        def apacheLibraries = []
        boolean includesLibyuv = false
        libraries.get().sort().each { gav, artifact ->
            String ga = gav.split(':').take(2).join(':')
            File pom = poms.get()[gav]
            if (!pom) throw new GradleException("Missing POM for $gav; review its license before shipping.")
            def xml = new XmlSlurper().parse(pom)
            def licenses = xml.licenses.license.collect { it.url.text() }
            if (ga == 'androidx.camera:camera-core' && licenses.contains(
                    'https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/README.chromium') &&
                    licenses.every { isApache(it) || it == 'https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/README.chromium' }) {
                apacheLibraries.add(gav)
                includesLibyuv = true
            } else if ((licenses.isEmpty() && APACHE_OVERRIDES.contains(gav)) || (licenses && licenses.every { isApache(it) })) {
                apacheLibraries.add(gav)
            } else if (licenses && licenses.every { isGoogleTerms(it) } &&
                    (ga.startsWith('com.google.android.gms:') || ga.startsWith('com.google.mlkit:') ||
                     ga == 'com.google.android.odml:image')) {
                // Proprietary SDK terms remain linked in Credits. Preserve their embedded OSS notices below.
            } else {
                throw new GradleException("Unreviewed license for $gav: $licenses. Add its full text and mapping to GenerateLicenseNotices.")
            }
            entries.addAll(extractNotices(artifact.bytes, gav))
        }
        entries.add(0, [name: 'Apache License 2.0', body:
            'Libraries in this build:\n\n' + apacheLibraries.join('\n') + '\n\n' +
                new File(licenseDirectory.get().asFile, 'Apache-2.0.txt').getText('UTF-8')])
        licenseDirectory.get().asFile.listFiles().findAll { it.name.endsWith('-OFL.txt') }.sort().each { file ->
            entries.add([name: file.name.replace('-OFL.txt', '').replace('-', ' ') + ' — Font license',
                         body: file.getText('UTF-8')])
        }
        if (includesLibyuv) {
            entries.add([name: 'libyuv — CameraX image processing', body:
                new File(licenseDirectory.get().asFile, 'libyuv-LICENSE.txt').getText('UTF-8') + '\n\n' +
                new File(licenseDirectory.get().asFile, 'libyuv-PATENTS.txt').getText('UTF-8')])
        }
        // Preserve notices verbatim; merge only identical name/text records.
        entries = entries.unique { [it.name, it.body] }
        File output = new File(outputDirectory.get().asFile, 'open_source_licenses.json')
        output.parentFile.mkdirs()
        output.setText(JsonOutput.toJson(entries), 'UTF-8')
        logger.lifecycle("Bundled ${libraries.get().size()} runtime libraries and ${entries.size()} license/notice entries")
    }

    static boolean isApache(String url) {
        url ==~ /https?:\/\/(www\.)?apache.org\/licenses\/LICENSE-2.0(\.txt|\.html)?\/?/
    }

    static boolean isGoogleTerms(String url) {
        url in ['https://developer.android.com/studio/terms.html', 'https://developers.google.com/ml-kit/terms']
    }

    static List<Map<String, String>> extractNotices(byte[] archive, String owner) {
        def files = new TreeMap<String, byte[]>()
        new ZipInputStream(new ByteArrayInputStream(archive)).withCloseable { zip ->
            for (def entry = zip.nextEntry; entry != null; entry = zip.nextEntry) {
                if (!entry.directory && (entry.name.endsWith('.jar') ||
                        entry.name ==~ /(?i).*(license|notice|copying).*/)) {
                    files[entry.name] = zip.readAllBytes()
                }
            }
        }
        def notices = []
        files.each { path, bytes ->
            if (path.endsWith('.jar')) {
                notices.addAll(extractNotices(bytes, owner))
            } else if (path.endsWith('third_party_licenses.json')) {
                String textPath = path.replaceAll(/\.json$/, '.txt')
                byte[] text = files[textPath]
                if (text == null) throw new GradleException("Missing embedded notice text: $owner/$textPath")
                def metadata = new JsonSlurper().parse(bytes, 'UTF-8')
                metadata.each { name, range ->
                    int start = range.start as int
                    int length = range.length as int
                    if (start < 0 || length < 0 || start > text.length || length > text.length - start) {
                        throw new GradleException("Invalid embedded notice range: $owner/$name")
                    }
                    int count = length == 0 ? text.length - start : length
                    notices.add([name: "$owner — $name".toString(), body: new String(text, start, count, 'UTF-8')])
                }
            } else if (path ==~ /(?i)(META-INF\/.*|)(LICENSE|NOTICE|COPYING)([._-].*)?/ &&
                       !path.endsWith('.class')) {
                notices.add([name: "$owner — $path".toString(), body: new String(bytes, 'UTF-8')])
            }
        }
        notices
    }
}
