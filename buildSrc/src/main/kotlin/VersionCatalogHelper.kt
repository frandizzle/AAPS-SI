@file:Suppress("unused")

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.logging.Logger

class VersionCatalogHelper(private val project: Project) {

    private val versionCatalog = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    private val logger: Logger = project.logger

    fun dependency(libName: String, configurationName: String) {
        versionCatalog.findLibrary(libName).ifPresentOrElse(
            { library ->
                val dependency = project.dependencies.add(configurationName, library)
                if (libName == "org-skyscreamer-jsonassert" && (configurationName == "androidTestImplementation" || configurationName == "testImplementation")) {
                    (dependency as? org.gradle.api.artifacts.ExternalModuleDependency)?.exclude(mapOf("group" to "org.json", "module" to "json"))
                }
            },
            { throw IllegalArgumentException("Library '$libName' not found in version catalog.") }
        )
    }
}

// Extension functions for different configurations
fun Project.implementationFromCatalog(libName: String) {
    VersionCatalogHelper(this).dependency(libName, "implementation")
}

fun Project.testImplementationFromCatalog(libName: String) {
    VersionCatalogHelper(this).dependency(libName, "testImplementation")
}

fun Project.androidTestImplementationFromCatalog(libName: String) {
    VersionCatalogHelper(this).dependency(libName, "androidTestImplementation")
}

fun Project.compileOnlyFromCatalog(libName: String) {
    VersionCatalogHelper(this).dependency(libName, "compileOnly")
}

fun Project.testRuntimeOnlyFromCatalog(libName: String) {
    VersionCatalogHelper(this).dependency(libName, "testRuntimeOnly")
}