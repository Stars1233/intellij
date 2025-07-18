package com.google.idea.blaze.clwb;

import static com.google.common.truth.Truth.assertThat;
import static com.google.idea.blaze.clwb.base.Assertions.assertContainsHeader;
import static com.google.idea.blaze.clwb.base.Assertions.assertDefine;

import com.google.idea.blaze.base.bazel.BazelVersion;
import com.google.idea.blaze.clwb.base.ClwbHeadlessTestCase;
import com.google.idea.testing.headless.BazelVersionRule;
import com.google.idea.testing.headless.ProjectViewBuilder;
import com.google.idea.testing.headless.OSRule;
import com.intellij.util.system.OS;
import com.jetbrains.cidr.lang.workspace.compiler.ClangCompilerKind;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LlvmToolchainTest extends ClwbHeadlessTestCase {

  // llvm toolchain currently does not support windows, otherwise this test should be fine to run on windows
  @Rule
  public final OSRule osRule = new OSRule(OS.Linux, OS.macOS);

  // llvm toolchain currently only supports bazel 7+
  @Rule
  public final BazelVersionRule bazelRule = new BazelVersionRule(7, 0);

  @Override
  protected ProjectViewBuilder projectViewText(BazelVersion version) {
    // required because this test targets wasm
    return super.projectViewText(version).addBuildFlag("--platforms=@toolchains_llvm//platforms:wasm32");
  }

  @Test
  public void testClwb() {
    final var errors = runSync(defaultSyncParams().build());
    errors.assertNoErrors();

    checkCompiler();
  }

  private void checkCompiler() {
    final var compilerSettings = findFileCompilerSettings("main/hello-world.cc");

    assertThat(compilerSettings.getCompilerKind()).isEqualTo(ClangCompilerKind.INSTANCE);
    assertDefine("__llvm__", compilerSettings).isNotEmpty();
    assertDefine("__VERSION__", compilerSettings).startsWith("\"Clang 19.1.0");

    assertContainsHeader("stdlib.h", compilerSettings);
    assertContainsHeader("wasi/wasip2.h", compilerSettings);
  }
}
