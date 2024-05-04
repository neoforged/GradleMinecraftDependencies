package net.neoforged.minecraftmetadata

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

interface HasMinecraftVersion {
    @Input
    abstract Property<String> getModuleVersion()
}