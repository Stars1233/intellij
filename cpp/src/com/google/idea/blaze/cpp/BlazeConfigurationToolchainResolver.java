/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.cpp;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.base.async.executor.BlazeExecutor;
import com.google.idea.blaze.base.ideinfo.CToolchainIdeInfo;
import com.google.idea.blaze.base.ideinfo.Dependency;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.primitives.ExecutionRootPath;
import com.google.idea.blaze.base.model.primitives.LanguageClass;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.Scope;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.scope.scopes.TimingScope;
import com.google.idea.blaze.base.scope.scopes.TimingScope.EventType;
import com.google.idea.blaze.base.sync.BlazeSyncManager;
import com.google.idea.blaze.base.sync.workspace.ExecutionRootPathResolver;
import com.google.idea.blaze.cpp.CompilerVersionChecker.VersionCheckException;
import com.google.idea.blaze.cpp.XcodeCompilerSettingsProvider.XcodeCompilerSettingsException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.pom.NavigatableAdapter;

import java.io.File;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Converts {@link CToolchainIdeInfo} to interfaces used by {@link
 * com.jetbrains.cidr.lang.workspace.OCResolveConfiguration}
 */
public final class BlazeConfigurationToolchainResolver {
  private static final Logger logger =
      Logger.getInstance(BlazeConfigurationToolchainResolver.class);

  private BlazeConfigurationToolchainResolver() {}

  /** Returns the C toolchain used by each C target */
  @VisibleForTesting
  public static ImmutableMap<TargetKey, CToolchainIdeInfo> buildToolchainLookupMap(
      BlazeContext context, TargetMap targetMap) {
    return Scope.push(
        context,
        childContext -> {
          childContext.push(new TimingScope("Build toolchain lookup map", EventType.Other));

          ImmutableMap<TargetKey, CToolchainIdeInfo> toolchains =
              targetMap.targets().stream()
                  .filter(target -> target.getcToolchainIdeInfo() != null)
                  .collect(
                      toImmutableMap(TargetIdeInfo::getKey, TargetIdeInfo::getcToolchainIdeInfo));

          ImmutableMap<TargetIdeInfo, List<TargetKey>> toolchainDepsTable =
              buildToolchainDepsTable(targetMap.targets(), toolchains);
          verifyToolchainDeps(context, toolchainDepsTable);
          return buildLookupTable(toolchainDepsTable, toolchains);
        });
  }

  private static ImmutableMap<TargetIdeInfo, List<TargetKey>> buildToolchainDepsTable(
      ImmutableCollection<TargetIdeInfo> targets, Map<TargetKey, CToolchainIdeInfo> toolchains) {
    ImmutableMap.Builder<TargetIdeInfo, List<TargetKey>> toolchainDepsTable =
        ImmutableMap.builder();
    for (TargetIdeInfo target : targets) {
      if (!target.getKind().hasLanguage(LanguageClass.C) || target.getcToolchainIdeInfo() != null) {
        continue;
      }
      ImmutableList<TargetKey> toolchainDeps =
          target.getDependencies().stream()
              .map(Dependency::getTargetKey)
              .filter(toolchains::containsKey)
              .collect(toImmutableList());
      toolchainDepsTable.put(target, toolchainDeps);
    }
    return toolchainDepsTable.build();
  }

  private static void verifyToolchainDeps(
      BlazeContext context, ImmutableMap<TargetIdeInfo, List<TargetKey>> toolchainDepsTable) {
    ListMultimap<Integer, TargetIdeInfo> warningTargets = ArrayListMultimap.create();
    for (Map.Entry<TargetIdeInfo, List<TargetKey>> entry : toolchainDepsTable.entrySet()) {
      List<TargetKey> toolchainDeps = entry.getValue();
      if (toolchainDeps.size() != 1) {
        TargetIdeInfo target = entry.getKey();
          warningTargets.put(toolchainDeps.size(), target);
      }
    }
    issueToolchainWarning(context, warningTargets);
  }

  private static ImmutableMap<TargetKey, CToolchainIdeInfo> buildLookupTable(
      ImmutableMap<TargetIdeInfo, List<TargetKey>> toolchainDepsTable,
      ImmutableMap<TargetKey, CToolchainIdeInfo> toolchains) {
    ImmutableMap.Builder<TargetKey, CToolchainIdeInfo> lookupTable = ImmutableMap.builder();
    for (Map.Entry<TargetIdeInfo, List<TargetKey>> entry : toolchainDepsTable.entrySet()) {
      TargetIdeInfo target = entry.getKey();
      List<TargetKey> toolchainDeps = entry.getValue();
      if (!toolchainDeps.isEmpty()) {
        TargetKey toolchainKey = toolchainDeps.get(0);
        CToolchainIdeInfo toolchainInfo = toolchains.get(toolchainKey);
        lookupTable.put(target.getKey(), toolchainInfo);
      } else {
        CToolchainIdeInfo arbitraryToolchain = Iterables.getFirst(toolchains.values(), null);
        if (arbitraryToolchain != null) {
          lookupTable.put(target.getKey(), arbitraryToolchain);
        }
      }
    }
    return lookupTable.build();
  }

  private static void issueToolchainWarning(
      BlazeContext context, Multimap<Integer, TargetIdeInfo> warningTargets) {
    for (Map.Entry<Integer, Collection<TargetIdeInfo>> entry : warningTargets.asMap().entrySet()) {
      Map<Boolean, List<TargetIdeInfo>> partitionedTargets =
          entry.getValue().stream()
              .collect(
                  Collectors.partitioningBy(
                      BlazeConfigurationToolchainResolver::usesAppleCcToolchain));
      if (!partitionedTargets.get(Boolean.FALSE).isEmpty()) {
        String warningMessage =
            String.format(
                "cc target is expected to depend on exactly 1 cc toolchain. "
                    + "Found %d toolchains for these targets: %s",
                entry.getKey(),
                partitionedTargets.get(Boolean.FALSE).stream()
                    .map(TargetIdeInfo::getKey)
                    .map(TargetKey::toString)
                    .collect(joining(", ")));
        IssueOutput.warn(warningMessage).submit(context);
      }
      if (!partitionedTargets.get(Boolean.TRUE).isEmpty()) {
        logger.warn(
            String.format(
                "cc target is expected to depend on exactly 1 cc toolchain. "
                    + "Found %d toolchains for these targets with apple_cc_toolchain: %s.",
                entry.getKey(),
                partitionedTargets.get(Boolean.TRUE).stream()
                    .map(TargetIdeInfo::getKey)
                    .map(TargetKey::toString)
                    .collect(joining(", "))));
      }
    }
  }

  private static boolean usesAppleCcToolchain(TargetIdeInfo target) {
    return target.getDependencies().stream()
        .map(Dependency::getTargetKey)
        .map(TargetKey::getLabel)
        .map(TargetExpression::toString)
        .anyMatch(s -> s.startsWith("//tools/osx/crosstool"));
  }

  /**
   * Returns the compiler settings for each toolchain.
   */
  static ImmutableMap<CToolchainIdeInfo, BlazeCompilerSettings> buildCompilerSettingsMap(
      BlazeContext context,
      Project project,
      ImmutableMap<TargetKey, CToolchainIdeInfo> toolchainLookupMap,
      ExecutionRootPathResolver executionRootPathResolver,
      ImmutableMap<CToolchainIdeInfo, BlazeCompilerSettings> oldCompilerSettings,
      Optional<XcodeCompilerSettings> xcodeCompilerSettings
  ) {
    return Scope.push(
        context,
        childContext -> {
          childContext.push(new TimingScope("Build compiler settings map", EventType.Other));
          return doBuildCompilerSettingsMap(
              context, project, toolchainLookupMap, executionRootPathResolver,
              xcodeCompilerSettings, oldCompilerSettings);
        });
  }

  private static @Nullable File resolveCompilerExecutable(
      BlazeContext context,
      ExecutionRootPathResolver executionRootPathResolver,
      ExecutionRootPath compilerPath
  ) {
    File compilerFile = executionRootPathResolver.resolveExecutionRootPath(compilerPath);

    if (compilerFile == null) {
      IssueOutput.error("Unable to find compiler executable: " + compilerPath).submit(context);
      return null;
    }

    if (!compilerFile.exists() && SystemInfo.isWindows) {
      // bazel reports the compiler executable without the exe suffix
      compilerFile = new File(compilerFile.getAbsolutePath() + ".exe");
    }

    return compilerFile;
  }

  private static @Nullable String mergeCompilerVersions(
      BlazeContext context,
      @Nullable String cVersion,
      @Nullable String cppVersion) {
    if (cVersion == null) {
      return cppVersion;
    }
    if (cppVersion == null) {
      return cVersion;
    }
    if (cVersion.equals(cppVersion)) {
      return cppVersion;
    }

    IssueOutput.warn("C and Cpp compiler version mismatch. Defaulting to Cpp compiler version.")
        .submit(context);

    return cppVersion;
  }

  private static ImmutableMap<CToolchainIdeInfo, BlazeCompilerSettings> doBuildCompilerSettingsMap(
      BlazeContext context,
      Project project,
      ImmutableMap<TargetKey, CToolchainIdeInfo> toolchainLookupMap,
      ExecutionRootPathResolver executionRootPathResolver,
      Optional<XcodeCompilerSettings> xcodeCompilerSettings,
      ImmutableMap<CToolchainIdeInfo, BlazeCompilerSettings> oldCompilerSettings) {
    Set<CToolchainIdeInfo> toolchains = new HashSet<>(toolchainLookupMap.values());
    List<ListenableFuture<Map.Entry<CToolchainIdeInfo, BlazeCompilerSettings>>>
        compilerSettingsFutures = new ArrayList<>();
    for (CToolchainIdeInfo toolchain : toolchains) {
      compilerSettingsFutures.add(
          submit(
              () -> {
                File cCompiler = resolveCompilerExecutable(context, executionRootPathResolver,
                    toolchain.cCompiler());
                if (cCompiler == null) {
                  return null;
                }
                File cppCompiler = resolveCompilerExecutable(context, executionRootPathResolver,
                    toolchain.cppCompiler());
                if (cppCompiler == null) {
                  return null;
                }

                String cCompilerVersion = getCompilerVersion(project, context,
                    executionRootPathResolver, xcodeCompilerSettings, cCompiler);
                String cppCompilerVersion = getCompilerVersion(project, context,
                    executionRootPathResolver, xcodeCompilerSettings, cppCompiler);

                String compilerVersion = mergeCompilerVersions(context, cCompilerVersion,
                    cppCompilerVersion);
                if (compilerVersion == null) {
                  return null;
                }

                BlazeCompilerSettings oldSettings = oldCompilerSettings.get(toolchain);
                if (oldSettings != null && oldSettings.version().equals(compilerVersion)) {
                  return new SimpleImmutableEntry<>(toolchain, oldSettings);
                }
                BlazeCompilerSettings settings =
                    createBlazeCompilerSettings(
                        context,
                        project,
                        toolchain,
                        xcodeCompilerSettings,
                        executionRootPathResolver.getExecutionRoot(),
                        cCompiler,
                        cppCompiler,
                        compilerVersion);
                if (settings == null) {
                  return null;
                }
                return new SimpleImmutableEntry<>(toolchain, settings);
              }));
    }
    ImmutableMap.Builder<CToolchainIdeInfo, BlazeCompilerSettings> compilerSettingsMap =
        ImmutableMap.builder();
    try {
      List<Map.Entry<CToolchainIdeInfo, BlazeCompilerSettings>> createdSettings =
          Futures.allAsList(compilerSettingsFutures).get();
      for (Map.Entry<CToolchainIdeInfo, BlazeCompilerSettings> createdSetting : createdSettings) {
        if (createdSetting != null) {
          compilerSettingsMap.put(createdSetting);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      context.setCancelled();
    } catch (ExecutionException e) {
      IssueOutput.error("Could not build C compiler settings map: " + e).submit(context);
    }
    return compilerSettingsMap.build();
  }

  @Nullable
  private static String getCompilerVersion(
      Project project,
      BlazeContext context,
      ExecutionRootPathResolver executionRootPathResolver,
      Optional<XcodeCompilerSettings> xcodeCompilerSettings,
      File executable) {
    File executionRoot = executionRootPathResolver.getExecutionRoot();
    ImmutableMap<String, String> compilerEnvFlags = XcodeCompilerSettingsProvider.getInstance(project).asEnvironmentVariables(xcodeCompilerSettings);
    try {
      return CompilerVersionChecker.getInstance()
          .checkCompilerVersion(executionRoot, executable, compilerEnvFlags);
    } catch (VersionCheckException e) {
      switch (e.kind) {
        case MISSING_EXEC_ROOT:
          IssueOutput.warn(
                  String.format(
                      "Missing execution root %s (checking compiler).\n"
                          + "Double-click to run sync and create the execution root.",
                      executionRoot.getAbsolutePath()))
              .withNavigatable(
                  new NavigatableAdapter() {
                    @Override
                    public void navigate(boolean requestFocus) {
                      BlazeSyncManager.getInstance(project)
                          .incrementalProjectSync(
                              /* reason= */ "BlazeConfigurationToolchainResolver");
                    }
                  })
              .submit(context);
          return null;
        case MISSING_COMPILER:
          IssueOutput.warn(
                  String.format(
                      "Unable to access compiler executable \"%s\".\n"
                          + "Check if it is accessible from the cmdline.",
                      executable.getAbsolutePath()))
              .submit(context);
          return null;
        case GENERIC_FAILURE:
          IssueOutput.warn(
                  String.format(
                      "Unable to check compiler version for \"%s\".\n%s\n"
                          + "Check if running the compiler with --version works on the cmdline.",
                      executable.getAbsolutePath(), e.getMessage()))
              .submit(context);
          return null;
      }
      return null;
    }
  }

  @Nullable
  private static BlazeCompilerSettings createBlazeCompilerSettings(
      BlazeContext context,
      Project project,
      CToolchainIdeInfo toolchainIdeInfo,
      Optional<XcodeCompilerSettings> xcodeCompilerSettings,
      File executionRoot,
      File cCompiler,
      File cppCompiler,
      String compilerVersion) {
    final var compilerWrapperEnvVars = XcodeCompilerSettingsProvider.getInstance(project)
        .asEnvironmentVariables(xcodeCompilerSettings);

    final var cCompilerWrapper = CompilerWrapperProvider.getInstance()
        .createCompilerExecutableWrapper(executionRoot, cCompiler, compilerWrapperEnvVars);
    if (cCompilerWrapper == null) {
      IssueOutput.error("Unable to create compiler wrapper for: " + cCompiler).submit(context);
      return null;
    }

    final var cppCompilerWrapper = CompilerWrapperProvider.getInstance()
        .createCompilerExecutableWrapper(executionRoot, cppCompiler, compilerWrapperEnvVars);
    if (cppCompilerWrapper == null) {
      IssueOutput.error("Unable to create compiler wrapper for: " + cppCompiler).submit(context);
      return null;
    }

    return BlazeCompilerSettings.builder()
        .setCCompiler(cCompilerWrapper)
        .setCppCompiler(cppCompilerWrapper)
        .setCSwitches(BlazeCompilerFlagsProcessor.process(project, toolchainIdeInfo.cCompilerOptions()))
        .setCppSwitches(BlazeCompilerFlagsProcessor.process(project, toolchainIdeInfo.cppCompilerOptions()))
        .setVersion(compilerVersion)
        .setEnvironment(compilerWrapperEnvVars)
        .setBuiltInIncludes(toolchainIdeInfo.builtInIncludeDirectories())
        .setName(toolchainIdeInfo.compilerName())
        .setSysroot(toolchainIdeInfo.sysroot())
        .build();
  }

  private static <T> ListenableFuture<T> submit(Callable<T> callable) {
    return BlazeExecutor.getInstance().submit(callable);
  }

  public static Optional<XcodeCompilerSettings> resolveXcodeCompilerSettings(BlazeContext context,
      Project project) {
    return Scope.push(
        context,
        childContext -> {
          childContext.push(new TimingScope("Resolve Xcode information", EventType.Other));
          try {
            return XcodeCompilerSettingsProvider.getInstance(project).fromContext(context, project);
          } catch (XcodeCompilerSettingsException e) {
            IssueOutput.warn(
                String.format("There was an error fetching the Xcode information from the build: %s\n\nSome C++ functionality may not be available.", e.toString())
            ).submit(childContext);
            return Optional.empty();
          }
        });
  }
}
