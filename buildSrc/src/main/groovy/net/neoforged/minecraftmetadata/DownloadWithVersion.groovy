package net.neoforged.minecraftmetadata

import de.undercouch.gradle.tasks.download.Download

import javax.inject.Inject

abstract class DownloadWithVersion extends Download implements HasMinecraftVersion {
    @Inject
    DownloadWithVersion() {
        this.moduleVersion.convention(project.providers.gradleProperty('minecraftVersion').orElse('undefined'))
    }
}
