package net.neoforged.minecraftdependencies

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.apache.maven.artifact.versioning.ComparableVersion
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
import java.util.jar.JarInputStream
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@CompileStatic
abstract class GenerateModuleMetadata extends DefaultTask implements HasMinecraftVersion {
    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getMeta()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getServerJar()

    @Input
    abstract Property<String> getModuleGroup()

    @Input
    abstract Property<String> getModuleName()

    @Inject
    GenerateModuleMetadata() {
        this.moduleVersion.convention(project.providers.gradleProperty('minecraftVersion').orElse('undefined'))
    }

    private static final Map<String, String> potentialLibraryUpgrades = Map.of(
            // 1.7.10 server jar contained guava 16 instead of 15
            "com.google.guava:guava:15.0", "com.google.guava:guava:16.0",
            "org.apache.commons:commons-lang3:3.1", "org.apache.commons:commons-lang3:3.2.1"
    )

    @TaskAction
    void run() {
        Map metadata = [:]
        metadata.formatVersion = "1.1"
        metadata.component = [
                group  : moduleGroup.get(),
                module : moduleName.get(),
                version: moduleVersion.get()
        ]
        List variants = []
        metadata.variants = variants

        List<String> clientDeps = []
        List<String> clientCompileOnlyDeps = []
        List<String> serverDeps = []
        Map<String, List<String>> clientNatives = [:]
        getMcDeps(serverDeps, clientDeps, clientCompileOnlyDeps, clientNatives)

        Map metaJson = new JsonSlurper().parse(meta.get().asFile) as Map
        int javaVersion = (metaJson.javaVersion as Map).majorVersion as int

        List clientDepEntries = []
        List serverDepEntries = []
        clientDepEntries.addAll(depsOf(clientDeps))
        serverDepEntries.addAll(depsOf(serverDeps))
        // Make a second list that depends on the natives at runtime
        List clientDepEntriesForRuntime = []
        clientDepEntriesForRuntime.addAll(clientDepEntries)
        clientDepEntriesForRuntime.add([
                group                : moduleGroup.get(),
                module               : moduleName.get(),
                version              : [strictly: moduleVersion.get()],
                endorseStrictVersions: true,
                requestedCapabilities: [
                        [
                                group: moduleGroup.get(),
                                name : moduleName.get() + "-natives"
                        ]
                ]
        ])
        // Add an entry for the objc-bridge which is also needed at compiletime regardless of platform
        def objcBridge = clientNatives["osx"].find { it.startsWith("ca.weblite:java-objc-bridge") }
        if (objcBridge) {
            clientDepEntries.add(depOf(objcBridge))
        }
        clientDepEntries.addAll(depsOf(clientCompileOnlyDeps))

        variants.add([
                name        : 'clientCompileDependencies',
                attributes  : [
                        'org.gradle.usage'          : 'java-api',
                        'org.gradle.jvm.version'    : javaVersion,
                        'net.neoforged.distribution': 'client'
                ],
                dependencies: clientDepEntries,
                capabilities: [[
                                       group  : moduleGroup.get(),
                                       name   : moduleName.get(),
                                       version: moduleVersion.get(),
                               ]]
        ])
        variants.add([
                name        : 'clientRuntimeDependencies',
                attributes  : [
                        'org.gradle.usage'          : 'java-runtime',
                        'org.gradle.jvm.version'    : javaVersion,
                        'net.neoforged.distribution': 'client'
                ],
                dependencies: clientDepEntriesForRuntime,
                capabilities: [[
                                       group  : moduleGroup.get(),
                                       name   : moduleName.get(),
                                       version: moduleVersion.get(),
                               ]]
        ])
        variants.add([
                name        : 'serverCompileDependencies',
                attributes  : [
                        'org.gradle.usage'          : 'java-api',
                        'org.gradle.jvm.version'    : javaVersion,
                        'net.neoforged.distribution': 'server'
                ],
                dependencies: serverDepEntries,
                capabilities: [[
                                       group  : moduleGroup.get(),
                                       name   : moduleName.get(),
                                       version: moduleVersion.get(),
                               ]]
        ])
        variants.add([
                name        : 'serverRuntimeDependencies',
                attributes  : [
                        'org.gradle.usage'          : 'java-runtime',
                        'org.gradle.jvm.version'    : javaVersion,
                        'net.neoforged.distribution': 'server'
                ],
                dependencies: serverDepEntries,
                capabilities: [[
                                       group  : moduleGroup.get(),
                                       name   : moduleName.get(),
                                       version: moduleVersion.get(),
                               ]]
        ])

        platforms.each { os ->
            List<String> nativeList = clientNatives.get(os) ?: []
            variants.add([
                    name        : 'client' + os.capitalize() + 'Natives',
                    attributes  : [
                            'org.gradle.usage'             : 'java-runtime',
                            'org.gradle.jvm.version'       : javaVersion,
                            'net.neoforged.distribution'   : 'client',
                            'net.neoforged.operatingsystem': os
                    ],
                    dependencies: depsOf(nativeList),
                    capabilities: [
                            [
                                    group  : moduleGroup.get(),
                                    name   : moduleName.get() + "-natives",
                                    version: moduleVersion.get()
                            ]
                    ]
            ])
        }

        getOutputFile().get().asFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(metadata))
    }

    private static final List<String> platforms = ["windows", "osx", "linux"]

    private Map<String, String> getFileFingerprints(String artifactId) {
        var listingFile = new File(temporaryDir, artifactId.replace(':', '-') + ".txt")
        if (listingFile.exists()) {
            return listingFile.readLines()
                    .stream()
                    .map(v -> v.split("\\|", 2))
                    .collect(Collectors.toMap((String[] v) -> v[0], (String[] v) -> v[1]))
        }

        var parts = artifactId.split(":")
        String relativeUrl
        if (parts.length == 3) {
            relativeUrl = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + ".jar"
        } else {
            relativeUrl = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + "-" + parts[3] + ".jar"
        }
        var url = URI.create("https://libraries.minecraft.net/" + relativeUrl).toURL()
        println("Getting $url")
        var hashes = new HashMap<String, String>()
        url.openStream().with { input ->
            var jin = new JarInputStream(input)
            for (var entry = jin.nextJarEntry; entry != null; entry = jin.nextJarEntry) {
                if (entry.name.endsWith("/")) {
                    continue
                }
                if (!entry.name.startsWith("META-INF/")) {
                    hashes[entry.name] = jin.readAllBytes().md5()
                }
            }
        }

        println("Hashed ${hashes.size()} files in jar")

        try (var writer = listingFile.newWriter()) {
            for (var entry in hashes.entrySet()) {
                writer.writeLine(entry.getKey() + "|" + entry.getValue())
            }
        }

        return hashes
    }

    private void getMcDeps(List<String> server, List<String> client, Map<String, List<String>> clientNatives) {
        Map metaJson = new JsonSlurper().parse(meta.get().asFile) as Map
        (metaJson.libraries as List<Map<String, Object>>).each { Map lib ->
            Map downloads = lib.downloads as Map
            List<String> allow = ((lib.rules ?: []) as List<Map<String, Object>>).findAll { it.action == "allow" }.collect { (it.os as Map)?.name as String }.findAll { it !== null }
            List<String> disallow = ((lib.rules ?: []) as List<Map<String, Object>>).findAll { it.action == "disallow" }.collect { (it.os as Map)?.name as String }.findAll { it !== null }
            if (downloads.artifact) {
                if (allow.empty && disallow.empty) {
                    client.add lib.name as String
                } else {
                    List<String> platforms = new ArrayList<>(allow.empty ? platforms : allow)
                    platforms.removeAll(disallow)
                    platforms.each {
                        clientNatives.computeIfAbsent(it, { [] }).add(lib.name as String)
                    }
                }
            }
            if (lib.natives) {
                Map<String, String> natives = lib.natives as Map<String, String>
                natives.each { platform, classifier ->
                    clientNatives.computeIfAbsent(platform, { [] }).add("${lib.name}:${classifier}" as String)
                }
            }
        }

        // Remove duplicates in natives
        for (var libs in clientNatives.values()) {
            var dedupedLibs = new LinkedHashSet(libs)
            libs.clear()
            libs.addAll(dedupedLibs)
        }

        // Promote natives to compile time dependencies if the same G+A+Classifier is present for all platforms
        // Use the lowest version. This happens on some versions (i.e. 1.18.2) where lwjgl and all such dependencies
        // are used in a lower version on OSX
        for (String windowNativeArtifact in clientNatives.getOrDefault(platforms[0], [])) {
            var coordinate = MavenCoordinate.parse(windowNativeArtifact)
            var version = new ComparableVersion(coordinate.version())

            var inAllPlatforms = true
            for (var otherPlatform in platforms.drop(1)) {
                var found = false
                for (var otherPlatformArtifact in clientNatives.getOrDefault(otherPlatform, [])) {
                    var otherPlatformCoordinate = MavenCoordinate.parse(otherPlatformArtifact)
                    if (coordinate.equalsIgnoringVersion(otherPlatformCoordinate)) {
                        found = true
                        var otherVersion = new ComparableVersion(otherPlatformCoordinate.version())
                        if (otherVersion < version) {
                            version = otherVersion // use lowest
                        }
                        break
                    }
                }
                if (!found) {
                    inAllPlatforms = false
                    break
                }
            }

            if (inAllPlatforms) {
                println("Promoting " + coordinate + " (" + version + ") from natives to compile time dependency")
                coordinate = coordinate.withVersion(version.toString())
                if (client.stream().map(MavenCoordinate::parse).noneMatch { c -> c.equalsIgnoringVersion(coordinate) }) {
                    clientCompileOnly.add(coordinate.toString())
                }
            }
        }

        try (def zf = new ZipFile(serverJar.get().getAsFile())) {
            def librariesListEntry = zf.getEntry('META-INF/libraries.list')
            if (librariesListEntry != null) {
                try (def is = zf.getInputStream(librariesListEntry)) {
                    is.readLines().each {
                        server.add(it.split('\t')[1])
                    }
                }
            } else {
                // This will be slow.

                // Fingerprint all files found in the server jar
                var fileFingerprints = new HashMap<String, String>();
                zf.entries().iterator().each { ZipEntry it ->
                    if (it.name.endsWith("/")) {
                        return
                    }
                    zf.getInputStream(it).withCloseable { stream ->
                        fileFingerprints[it.name] = stream.readAllBytes().md5()
                    }
                }

                var libraries = new LinkedHashSet<String>()

                // For each client library, find the list of folders contained within
                // We need to do this in order of the declared libraries since mojang chose to fix log4j2 issues
                // by introducing a higherpriority library that contains files from log4j2 and netty
                for (var artifactId in client) {
                    var result = matchLibraryAgainstServerJar(artifactId, fileFingerprints)
                    while (result == null) {
                        // Result was uncertain
                        var upgradedLib = potentialLibraryUpgrades[artifactId]
                        if (upgradedLib) {
                            println("Trying upgrade from $artifactId to $upgradedLib")
                            artifactId = upgradedLib
                            result = matchLibraryAgainstServerJar(upgradedLib, fileFingerprints)
                            if (result) {
                                break // Upgrade successful
                            }
                        } else {
                            break
                        }
                    }

                    if (result == null) {
                        println("*** Including partial match: $artifactId")
                        libraries.add(artifactId)
                    } else if (result) {
                        libraries.add(artifactId)
                    }
                }

                server.addAll(libraries)
            }
        }
    }

    private Boolean matchLibraryAgainstServerJar(String artifactId, Map<String, String> fileFingerprints) {
        var libFileFingerprints = getFileFingerprints(artifactId)
        var matches = 0
        var mismatches = new ArrayList<String>()
        var missing = new ArrayList<String>()
        for (var entry in libFileFingerprints.entrySet()) {
            if (fileFingerprints.containsKey(entry.key)) {
                if (fileFingerprints[entry.key] == entry.value) {
                    matches++
                } else {
                    mismatches.add(entry.key)
                }
            } else {
                missing.add(entry.key)
            }
        }
        if (matches == 0 && mismatches.size() == 0) {
            return false // Assuredly missing
        } else if (matches == libFileFingerprints.size()) {
            println(" Matched $artifactId")
            return true
        } else {
            println(" Not sure about $artifactId matches=$matches mismatches=${mismatches.size()} missing=${missing.size()}")
            return null
        }
    }

    private static List depsOf(List<String> deps) {
        return deps.unique(false).collect { depOf(it) }
    }

    private static Map depOf(String notation) {
        String[] parts = notation.split(':')
        Map map = [:]
        map.excludes = [[group: '*', module: '*']]
        map.group = parts[0]
        map.module = parts[1]
        map.version = [
                strictly: parts[2]
        ]
        if (parts.length > 3 || parts[2].contains('@')) {
            Map artifactSelector = [:]
            String extension = 'jar'
            if (parts.length > 3) {
                String[] moreParts = parts[3].split('@')
                artifactSelector.classifier = moreParts[0]
                if (moreParts.length > 1) {
                    extension = moreParts[1]
                }
            } else {
                String[] moreParts = parts[2].split('@')
                map.version = [
                        strictly: moreParts[0]
                ]
                extension = moreParts[1]
            }
            artifactSelector.extension = extension
            artifactSelector.type = extension
            artifactSelector.name = parts[1]
            map.thirdPartyCompatibility = ['artifactSelector': artifactSelector]
        }
        return map
    }
}
