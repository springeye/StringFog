package com.github.megatronking.stringfog.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class StringFogPlugin : Plugin<Project> {

    companion object {
        private const val PLUGIN_NAME = "stringfog"
        private val ANDROID_PLUGIN_IDS = listOf(
            "com.android.application",
            "com.android.library",
            "com.android.dynamic-feature"
        )
    }

    override fun apply(project: Project) {
        project.extensions.create(PLUGIN_NAME, StringFogExtension::class.java)

        // AGP 9: 延迟检测 android 插件，确保 AndroidComponentsExtension 已完全注册
        var configured = false
        for (pluginId in ANDROID_PLUGIN_IDS) {
            project.pluginManager.withPlugin(pluginId) {
                if (!configured) {
                    configured = true
                    configureStringFog(project)
                }
            }
        }

        project.afterEvaluate {
            if (!configured) {
                throw GradleException("StringFog plugin must be used with android plugin")
            }
        }
    }

    private fun configureStringFog(project: Project) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        androidComponents.onVariants { variant ->
            val stringfog = project.extensions.getByType(StringFogExtension::class.java)
            if (stringfog.implementation.isNullOrEmpty()) {
                throw IllegalArgumentException("Missing stringfog implementation config")
            }
            if (!stringfog.enable) {
                return@onVariants
            }

            val applicationId = stringfog.packageName?.takeIf { it.isNotEmpty() }
                ?: variant.namespace.get().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Unable to resolve applicationId. Set 'packageName' in stringfog config or configure 'namespace' in android block.")

            val logs = mutableListOf<String>()
            variant.instrumentation.transformClassesWith(
                StringFogTransform::class.java,
                InstrumentationScope.PROJECT
            ) { params ->
                params.setParameters(
                    applicationId,
                    stringfog,
                    logs,
                    "$applicationId.${SourceGeneratingTask.FOG_CLASS_NAME}"
                )
            }
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )

            val variantName = variant.name
            val capitalizedName = variantName.replaceFirstChar { it.uppercase() }
            val generateTaskName = "generateStringFog${capitalizedName}"
            val stringfogDir = project.layout.buildDirectory.dir(
                "generated/source/stringFog/${variantName.lowercase()}"
            )
            val provider = project.tasks.register(generateTaskName, SourceGeneratingTask::class.java) { task ->
                task.genDir.set(stringfogDir)
                task.applicationId.set(applicationId)
                task.implementation.set(stringfog.implementation)
                task.mode.set(stringfog.mode)
            }
            variant.sources.java?.addGeneratedSourceDirectory(provider, SourceGeneratingTask::genDir)
        }
    }

}
