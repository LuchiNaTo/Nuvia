package localcomposite

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

fun Project.isLocalCompositeMediamp(): Boolean =
    providers.gradleProperty("mediamp.localComposite")
        .map { it.toBooleanStrictOrNull() ?: false }
        .orElse(false)
        .get()

fun Project.isMediampNativeBuildEnabled(): Boolean =
    providers.gradleProperty("mediamp.nativeBuild")
        .map { it.toBooleanStrictOrNull() ?: false }
        .orElse(false)
        .get()

fun KotlinMultiplatformExtension.configureAndroidNamespaceIfPresent(namespace: String) {
    val androidExtension = extensions.findByName("android") ?: return
    androidExtension::class.java.methods
        .firstOrNull { method ->
            method.name == "setNamespace" &&
                method.parameterCount == 1 &&
                method.parameterTypes.singleOrNull() == String::class.java
        }
        ?.invoke(androidExtension, namespace)
}
