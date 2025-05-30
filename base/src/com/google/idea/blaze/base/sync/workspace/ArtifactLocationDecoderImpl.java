/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.base.sync.workspace;

import com.google.idea.blaze.base.command.buildresult.LocalFileOutputArtifactWithoutDigest;
import com.google.idea.blaze.base.command.buildresult.SourceArtifact;
import com.google.idea.blaze.base.command.info.BlazeInfo;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.model.RemoteOutputArtifacts;
import com.google.idea.blaze.common.artifact.BlazeArtifact;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import javax.annotation.Nullable;

/** Decodes intellij_ide_info.proto ArtifactLocation file paths */
public final class ArtifactLocationDecoderImpl implements ArtifactLocationDecoder {
  private static final Logger LOG = Logger.getInstance(ArtifactLocationDecoderImpl.class);
  private final BlazeInfo blazeInfo;
  private final WorkspacePathResolver pathResolver;
  private final RemoteOutputArtifacts remoteOutputs;

  public ArtifactLocationDecoderImpl(
      BlazeInfo blazeInfo,
      WorkspacePathResolver pathResolver,
      RemoteOutputArtifacts remoteOutputs) {
    this.blazeInfo = blazeInfo;
    this.pathResolver = pathResolver;
    this.remoteOutputs = remoteOutputs;
  }

  @Override
  public BlazeArtifact resolveOutput(ArtifactLocation artifact) {
    if (artifact.isMainWorkspaceSourceArtifact()) {
      return new SourceArtifact(decode(artifact));
    }
    BlazeArtifact remoteOutput = remoteOutputs.findRemoteOutput(artifact);
    if (remoteOutput != null) {
      return remoteOutput;
    }
    return outputArtifactFromExecRoot(artifact);
  }

  @Override
  public File resolveSource(ArtifactLocation artifact) {
    File mainWorkspaceFile = tryToResolveExternalArtifactToMainWorkspace(artifact);
    if (mainWorkspaceFile != null) {
      return mainWorkspaceFile;
    }

    return artifact.isMainWorkspaceSourceArtifact()
        ? pathResolver.resolveToFile(artifact.getRelativePath())
        : null;
  }

  @Override
  public File decode(ArtifactLocation artifactLocation) {
    if (artifactLocation.isMainWorkspaceSourceArtifact()) {
      return pathResolver.resolveToFile(artifactLocation.getRelativePath());
    }

    File mainWorkspaceFile = tryToResolveExternalArtifactToMainWorkspace(artifactLocation);
    if (mainWorkspaceFile != null) {
      return mainWorkspaceFile;
    }

    String path =
        Paths.get(
                blazeInfo.getExecutionRoot().getPath(),
                artifactLocation.getExecutionRootRelativePath())
            .toString();
    // doesn't require file-system operations -- no attempt to resolve symlinks.
    return new File(FileUtil.toCanonicalPath(path));
  }

  private @Nullable File tryToResolveExternalArtifactToMainWorkspace(ArtifactLocation artifactLocation) {
    if (artifactLocation.isExternal()) {
      try {
        File realFile = blazeInfo.getExecutionRoot().toPath()
            .resolve(artifactLocation.getExecutionRootRelativePath()).toRealPath().toFile();
        if (pathResolver.getWorkspacePath(realFile) != null) {
          return realFile;
        }
      } catch (IOException ioException) {
        LOG.warn("Failed to resolve real path for " + artifactLocation.getExecutionRootRelativePath() +
                "\n" + ioException.getClass().getSimpleName() + ": " + ioException.getMessage());
        LOG.trace(ioException);
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ArtifactLocationDecoderImpl that = (ArtifactLocationDecoderImpl) o;
    return Objects.equals(blazeInfo, that.blazeInfo)
        && Objects.equals(pathResolver, that.pathResolver);
  }

  @Override
  public int hashCode() {
    return Objects.hash(blazeInfo, pathResolver);
  }

  /**
   * Derives a {@link LocalFileOutputArtifactWithoutDigest} from a generated {@link
   * ArtifactLocation} under blaze-out.
   *
   * <p>If the exec-root path is of an unexpected form, falls back to returning a {@link
   * SourceArtifact}.
   */
  private BlazeArtifact outputArtifactFromExecRoot(ArtifactLocation location) {
    // exec-root-relative path of the form 'blaze-out/mnemonic/genfiles/path'
    String execRootPath = location.getExecutionRootRelativePath();
    int ix1 = execRootPath.indexOf('/');
    int ix2 = execRootPath.indexOf('/', ix1 + 1);
    if (ix2 == -1) {
      return new SourceArtifact(decode(location));
    }
    return new LocalFileOutputArtifactWithoutDigest(
      decode(location), Path.of(execRootPath),
      Path.of(location.getRootExecutionPathFragment()).getNameCount());
  }
}
