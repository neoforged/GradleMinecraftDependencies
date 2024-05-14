package net.neoforged.minecraftdependencies

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import javax.inject.Inject
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

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getClientJar()

    @Input
    abstract Property<String> getModuleGroup()

    @Input
    abstract Property<String> getModuleName()

    @Inject
    GenerateModuleMetadata() {
        this.moduleVersion.convention(project.providers.gradleProperty('minecraftVersion').orElse('undefined'))
    }

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
        List<String> serverDeps = []
        Map<String, List> clientNatives = [:]
        getMcDeps(serverDeps, clientDeps, clientNatives)

        Map metaJson = new JsonSlurper().parse(meta.get().asFile) as Map
        int javaVersion = (metaJson.javaVersion as Map).majorVersion as int

        List clientDepEntries = []
        List serverDepEntries = []
        clientDepEntries.add([
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
        clientDepEntries.addAll(depsOf(clientDeps))
        serverDepEntries.addAll(depsOf(serverDeps))
        variants.add([
                name        : 'clientDependencies',
                attributes  : [
                        'org.gradle.jvm.version'    : javaVersion,
                        'net.neoforged.distribution': 'client'
                ],
                dependencies: clientDepEntries,
                capabilities: [[
                                       group  : moduleGroup.get(),
                                       name   : moduleName.get() + "-dependencies",
                                       version: moduleVersion.get(),
                               ]]
        ])
        variants.add([
                name        : 'serverDependencies',
                attributes  : [
                        'org.gradle.jvm.version'    : javaVersion,
                        'net.neoforged.distribution': 'server'
                ],
                dependencies: serverDepEntries,
                capabilities: [[
                                       group  : moduleGroup.get(),
                                       name   : moduleName.get() + "-dependencies",
                                       version: moduleVersion.get(),
                               ]]
        ])

        platforms.each { os ->
            List<String> nativeList = clientNatives.get(os) ?: []
            variants.add([
                    name        : 'client' + os.capitalize() + 'Natives',
                    attributes  : [
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
        try (def zf = new ZipFile(serverJar.get().getAsFile())) {
            def entry = zf.getEntry('META-INF/libraries.list')
            if (entry != null) {
                try (def is = zf.getInputStream(entry)) {
                    is.readLines().each {
                        server.add(it.split('\t')[1])
                    }
                }
            } else {
                throw new RuntimeException("Could not find libraries.list inside of server.jar")
            }
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
