/*
 * Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.qsync;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.common.Label;
import com.google.idea.blaze.qsync.project.PostQuerySyncData;
import com.google.idea.blaze.qsync.query.Query;
import com.google.idea.blaze.qsync.query.QueryData;
import com.google.idea.blaze.qsync.query.QuerySummary;
import com.google.idea.blaze.qsync.query.QuerySummaryImpl;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PartialProjectRefreshTest {

  @Test
  public void testApplyDelta_replacePackage() {
    QuerySummary base =
      QuerySummaryImpl.newBuilder()
        .putRules(
          QueryData.Rule.builderForTests()
            .label(Label.of("//my/build/package1:rule"))
            .ruleClass("java_library")
            .sources(ImmutableList.of(Label.of("//my/build/package1:Class1.java")))
            .build())
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:Class1.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:subpackage/AnotherClass.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:BUILD"), ImmutableList.of()))
        .putRules(
          QueryData.Rule.builderForTests()
            .label(Label.of("//my/build/package2:rule"))
            .ruleClass("java_library")
            .sources(ImmutableList.of(Label.of("//my/build/package2:Class2.java")))
            .build())
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package2:Class2.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package2:BUILD"), ImmutableList.of()))
        .build();
    PostQuerySyncData baseProject =
      PostQuerySyncData.EMPTY.toBuilder().setQuerySummary(base).build();

    QuerySummary delta =
      QuerySummaryImpl.newBuilder()
        .putRules(
          QueryData.Rule.builderForTests()
            .label(Label.of("//my/build/package1:newrule"))
            .ruleClass("java_library")
            .sources(ImmutableList.of(Label.of("//my/build/package1:NewClass.java")))
            .build())
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:NewClass.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:BUILD"), ImmutableList.of()))
        .build();

    PartialProjectRefresh queryStrategy =
      new PartialProjectRefresh(
        Path.of("/workspace/root"),
        baseProject,
        QuerySyncTestUtils.CLEAN_VCS_STATE,
        Optional.empty(),
        /* modifiedPackages= */ ImmutableSet.of(Path.of("my/build/package1")),
        ImmutableSet.of());
    QuerySummary applied = queryStrategy.applyDelta(delta);
    assertThat(applied.getRulesMap().keySet())
      .containsExactly(
        Label.of("//my/build/package1:newrule"), Label.of("//my/build/package2:rule"));
    assertThat(applied.getSourceFilesMap().keySet())
      .containsExactly(
        Label.of("//my/build/package1:NewClass.java"),
        Label.of("//my/build/package1:BUILD"),
        Label.of("//my/build/package2:Class2.java"),
        Label.of("//my/build/package2:BUILD"));
  }

  @Test
  public void testApplyDelta_deletePackage() {
    QuerySummary base =
      QuerySummaryImpl.newBuilder()
        .putRules(
          QueryData.Rule.builderForTests()
            .label(Label.of("//my/build/package1:rule"))
            .ruleClass("java_library")
            .sources(ImmutableList.of(Label.of("//my/build/package1:Class1.java")))
            .build())
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:Class1.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:subpackage/AnotherClass.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:BUILD"), ImmutableList.of()))
        .putRules(
          QueryData.Rule.builderForTests()
            .label(Label.of("//my/build/package2:rule"))
            .ruleClass("java_library")
            .sources(ImmutableList.of(Label.of("//my/build/package2:Class2.java")))
            .build())
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package2:Class2.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package2:BUILD"), ImmutableList.of()))
        .build();
    PostQuerySyncData baseProject =
      PostQuerySyncData.EMPTY.toBuilder().setQuerySummary(base).build();

    PartialProjectRefresh queryStrategy =
      new PartialProjectRefresh(
        Path.of("/workspace/root"),
        baseProject,
        QuerySyncTestUtils.CLEAN_VCS_STATE,
        Optional.empty(),
        ImmutableSet.of(),
        /* deletedPackages= */ ImmutableSet.of(Path.of("my/build/package1")));
    assertThat(queryStrategy.getQuerySpec()).isEmpty();
    QuerySummary applied = queryStrategy.applyDelta(QuerySummaryImpl.EMPTY);
    assertThat(applied.getRulesMap().keySet())
      .containsExactly(Label.of("//my/build/package2:rule"));
    assertThat(applied.getSourceFilesMap().keySet())
      .containsExactly(
        Label.of("//my/build/package2:Class2.java"), Label.of("//my/build/package2:BUILD"));
  }

  @Test
  public void testDelta_addPackage() {
    QuerySummary base =
      QuerySummaryImpl.newBuilder()
        .putRules(
          QueryData.Rule.builderForTests()
            .label(Label.of("//my/build/package1:rule"))
            .ruleClass("java_library")
            .sources(ImmutableList.of(Label.of("//my/build/package1:Class1.java")))
            .build())
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:Class1.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package1:BUILD"), ImmutableList.of()))
        .build();
    PostQuerySyncData baseProject =
      PostQuerySyncData.EMPTY.toBuilder().setQuerySummary(base).build();
    QuerySummary delta =
      QuerySummaryImpl.newBuilder()
        .putRules(
          QueryData.Rule.builderForTests()
            .label(Label.of("//my/build/package2:rule"))
            .ruleClass("java_library")
            .sources(ImmutableList.of(Label.of("//my/build/package2:Class2.java")))
            .build())
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package2:Class2.java"), ImmutableList.of()))
        .putSourceFiles(
          new QueryData.SourceFile(Label.of("//my/build/package2:BUILD"), ImmutableList.of()))
        .build();

    PartialProjectRefresh queryStrategy =
      new PartialProjectRefresh(
        Path.of("/workspace/root"),
        baseProject,
        QuerySyncTestUtils.CLEAN_VCS_STATE,
        Optional.empty(),
        /* modifiedPackages= */ ImmutableSet.of(Path.of("my/build/package2")),
        ImmutableSet.of());
    QuerySummary applied = queryStrategy.applyDelta(delta);
    assertThat(applied.getRulesMap().keySet())
      .containsExactly(
        Label.of("//my/build/package1:rule"), Label.of("//my/build/package2:rule"));
    assertThat(applied.getSourceFilesMap().keySet())
      .containsExactly(
        Label.of("//my/build/package1:Class1.java"),
        Label.of("//my/build/package1:BUILD"),
        Label.of("//my/build/package2:Class2.java"),
        Label.of("//my/build/package2:BUILD"));
  }

  @Test
  public void testDelta_packagesWithErrors() {
    QuerySummary base =
      QuerySummaryImpl.create(
        Query.Summary.newBuilder().addPackagesWithErrors("//my/build/package:BUILD").build());
    PostQuerySyncData baseProject =
      PostQuerySyncData.EMPTY.toBuilder().setQuerySummary(base).build();
    QuerySummary delta =
      QuerySummaryImpl.create(
        Query.Summary.newBuilder().addPackagesWithErrors("//my/build/package:BUILD").build());

    PartialProjectRefresh queryStrategy =
      new PartialProjectRefresh(
        Path.of("/workspace/root"),
        baseProject,
        QuerySyncTestUtils.CLEAN_VCS_STATE,
        Optional.empty(),
        /* modifiedPackages= */ ImmutableSet.of(Path.of("my/build/package")),
        ImmutableSet.of());
    QuerySummary applied = queryStrategy.applyDelta(delta);
    assertThat(applied.getPackagesWithErrors()).containsExactly(Path.of("my/build/package"));
  }
}
