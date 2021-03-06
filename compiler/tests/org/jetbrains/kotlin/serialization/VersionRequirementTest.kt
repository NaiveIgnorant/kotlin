/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.serialization

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.jvm.compiler.LoadDescriptorUtil
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.ProtoBuf.VersionRequirement.VersionKind.*
import org.jetbrains.kotlin.metadata.deserialization.VersionRequirement
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.lazy.JvmResolveUtil
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedMemberDescriptor
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestCaseWithTmpdir
import org.jetbrains.kotlin.test.TestJdkKind
import java.io.File

class VersionRequirementTest : TestCaseWithTmpdir() {
    fun doTest(
        expectedVersionRequirement: VersionRequirement.Version,
        expectedLevel: DeprecationLevel,
        expectedMessage: String?,
        expectedVersionKind: ProtoBuf.VersionRequirement.VersionKind,
        expectedErrorCode: Int?,
        customLanguageVersion: LanguageVersion = LanguageVersionSettingsImpl.DEFAULT.languageVersion,
        fqNames: List<String>
    ) {
        LoadDescriptorUtil.compileKotlinToDirAndGetModule(
            listOf(File("compiler/testData/versionRequirement/${getTestName(true)}.kt")), tmpdir,
            KotlinCoreEnvironment.createForTests(
                testRootDisposable,
                KotlinTestUtils.newConfiguration(ConfigurationKind.ALL, TestJdkKind.MOCK_JDK, tmpdir).apply {
                    put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_1_8)
                    languageVersionSettings = LanguageVersionSettingsImpl(
                        customLanguageVersion,
                        ApiVersion.createByLanguageVersion(customLanguageVersion),
                        mapOf(AnalysisFlag.jvmDefaultMode to JvmDefaultMode.ENABLE),
                        emptyMap()
                    )
                },
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
        )

        val (_, module) = JvmResolveUtil.analyze(
                KotlinCoreEnvironment.createForTests(
                        testRootDisposable,
                        KotlinTestUtils.newConfiguration(ConfigurationKind.ALL, TestJdkKind.MOCK_JDK, tmpdir),
                        EnvironmentConfigFiles.JVM_CONFIG_FILES
                )
        )

        fun check(descriptor: DeclarationDescriptor) {
            val requirement = when (descriptor) {
                is DeserializedMemberDescriptor -> descriptor.versionRequirement
                is DeserializedClassDescriptor -> descriptor.versionRequirement
                else -> throw AssertionError("Unknown descriptor: $descriptor")
            } ?: throw AssertionError("No VersionRequirement for $descriptor")

            assertEquals(expectedVersionRequirement, requirement.version)
            assertEquals(expectedLevel, requirement.level)
            assertEquals(expectedMessage, requirement.message)
            assertEquals(expectedVersionKind, requirement.kind)
            assertEquals(expectedErrorCode, requirement.errorCode)
        }

        for (fqName in fqNames) {
            check(module.findUnambiguousDescriptorByFqName(fqName))
        }
    }

    private fun ModuleDescriptor.findUnambiguousDescriptorByFqName(fqName: String): DeclarationDescriptor {
        val names = fqName.split('.')
        var descriptor: DeclarationDescriptor = getPackage(FqName(names.first()))
        for (name in names.drop(1)) {
            val descriptors = when (name) {
                "<init>" -> (descriptor as ClassDescriptor).constructors
                else -> {
                    val scope = when (descriptor) {
                        is PackageViewDescriptor -> descriptor.memberScope
                        is ClassDescriptor -> descriptor.unsubstitutedMemberScope
                        else -> error("Unsupported: $descriptor")
                    }
                    scope.getDescriptorsFiltered(nameFilter = { it.asString() == name })
                }
            }
            if (descriptors.isEmpty()) throw AssertionError("Descriptor not found: $name in $descriptor")
            descriptor = descriptors.singleOrNull() ?: throw AssertionError("Not a unambiguous descriptor: $name in $descriptor")
        }
        return descriptor
    }

    fun testSuspendFun() {
        doTest(
            VersionRequirement.Version(1, 1), DeprecationLevel.ERROR, null, LANGUAGE_VERSION, null,
            fqNames = listOf(
                "test.topLevel",
                "test.Foo.member",
                "test.Foo.<init>",
                "test.async1",
                "test.async2",
                "test.async3",
                "test.async4",
                "test.asyncVal"
            )
        )

        doTest(
            VersionRequirement.Version(1, 3), DeprecationLevel.ERROR, null, LANGUAGE_VERSION, null,
            customLanguageVersion = LanguageVersion.KOTLIN_1_3,
            fqNames = listOf(
                "test.topLevel",
                "test.Foo.member",
                "test.Foo.<init>",
                "test.async1",
                "test.async2",
                "test.async3",
                "test.async4",
                "test.asyncVal"
            )
        )
    }

    fun testLanguageVersionViaAnnotation() {
        doTest(
            VersionRequirement.Version(1, 1), DeprecationLevel.WARNING, "message", LANGUAGE_VERSION, 42,
            fqNames = listOf(
                "test.Klass",
                "test.Konstructor.<init>",
                "test.Typealias",
                "test.function",
                "test.property"
            )
        )
    }

    fun testApiVersionViaAnnotation() {
        doTest(
            VersionRequirement.Version(1, 1), DeprecationLevel.WARNING, "message", API_VERSION, 42,
            fqNames = listOf(
                "test.Klass",
                "test.Konstructor.<init>",
                "test.Typealias",
                "test.function",
                "test.property"
            )
        )
    }

    fun testCompilerVersionViaAnnotation() {
        doTest(
            VersionRequirement.Version(1, 1), DeprecationLevel.WARNING, "message", COMPILER_VERSION, 42,
            fqNames = listOf(
                "test.Klass",
                "test.Konstructor.<init>",
                "test.Typealias",
                "test.function",
                "test.property"
            )
        )
    }

    fun testPatchVersion() {
        doTest(
            VersionRequirement.Version(1, 1, 50), DeprecationLevel.HIDDEN, null, LANGUAGE_VERSION, null,
            fqNames = listOf("test.Klass")
        )
    }

    fun testJvmDefault() {
        doTest(
            VersionRequirement.Version(1, 2, 40), DeprecationLevel.ERROR, null, COMPILER_VERSION, null,
            fqNames = listOf(
                "test.Base",
                "test.Derived"
            )
        )
    }
}
