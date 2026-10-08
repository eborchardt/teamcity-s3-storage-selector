package com.eborchardt.teamcity.artifacts.s3selector;

import static com.eborchardt.teamcity.artifacts.s3selector.SelectArtifactsStorageStartBuildProcessor.AGENT_USE_S3_FLAG;
import static com.eborchardt.teamcity.artifacts.s3selector.SelectArtifactsStorageStartBuildProcessor.S3_STORAGE_ID_PARAM;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intellij.openapi.util.Pair;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import jetbrains.buildServer.artifacts.ArtifactStorageSettings;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.BuildAttributes;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.BuildStartContext;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SProjectFeatureDescriptor;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.buildLog.BuildLog;
import jetbrains.buildServer.serverSide.buildLog.MessageAttrs;
import jetbrains.buildServer.serverSide.storage.ArtifactsStorageSettingsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the full decision table of {@link SelectArtifactsStorageStartBuildProcessor}.
 *
 * <p>Observable effects asserted here are the three storage writes
 * ({@link BuildStartContext#addSharedParameter}, {@link BuildPromotionEx#setAttribute},
 * {@link BuildPromotionEx#persist}) and the user-facing build-log message. The symmetric
 * server-log INFO (success) and WARN (unresolved id) lines go through a static
 * {@code com.intellij.openapi.diagnostic.Logger} and are verified in the integration repro rather
 * than mocked here; the fail-safe contract they accompany (no storage writes, no build-log line)
 * is asserted below.
 */
@ExtendWith(MockitoExtension.class)
class SelectArtifactsStorageStartBuildProcessorTest {

  private static final String STORAGE_ID = "s3-storage-feature-id";

  @Mock private ArtifactsStorageSettingsManager settingsManager;
  @Mock private BuildStartContext context;
  @Mock private SRunningBuild build;
  @Mock private SBuildType buildType;
  @Mock private SProject project;
  @Mock private SBuildAgent agent;
  @Mock private BuildPromotionEx promotion;
  @Mock private BuildLog buildLog;

  private final Map<String, String> agentConfigParams = new HashMap<>();

  private SelectArtifactsStorageStartBuildProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new SelectArtifactsStorageStartBuildProcessor(settingsManager);

    // Common wiring. lenient() because not every row reaches every getter.
    lenient().when(context.getBuild()).thenReturn(build);
    lenient().when(build.getBuildType()).thenReturn(buildType);
    lenient().when(buildType.getProject()).thenReturn(project);
    lenient().when(build.getBuildPromotion()).thenReturn(promotion);
    lenient().when(build.getAgent()).thenReturn(agent);
    lenient().when(build.getBuildLog()).thenReturn(buildLog);
    lenient().when(agent.getConfigurationParameters()).thenReturn(agentConfigParams);
    lenient().when(build.getBuildId()).thenReturn(42L);
    lenient().when(project.getExternalId()).thenReturn("MyProject");
  }

  private void flagAgent(final boolean on) {
    if (on) {
      agentConfigParams.put(AGENT_USE_S3_FLAG, "true");
    }
  }

  private void setStorageIdParam(final String value) {
    when(promotion.getParameterValue(S3_STORAGE_ID_PARAM)).thenReturn(value);
  }

  private void storageResolves(final boolean resolves) {
    if (resolves) {
      // Any non-null Pair means "resolves"; its contents are irrelevant to the processor.
      when(settingsManager.findSettingsWithSource(eq(project), eq(STORAGE_ID)))
          .thenReturn(Pair.create(project, (SProjectFeatureDescriptor) null));
    } else {
      when(settingsManager.findSettingsWithSource(eq(project), anyString())).thenReturn(null);
    }
  }

  private void assertNoStorageWrites() {
    verify(context, never()).addSharedParameter(anyString(), anyString());
    verify(promotion, never()).setAttribute(anyString(), any());
    verify(promotion, never()).persist();
  }

  private void assertNoBuildLogMessage() {
    verify(buildLog, never()).messageAsync(anyString(), any(Status.class), any(MessageAttrs.class));
  }

  /** Row 1: project already resolves to an active storage -> respect it, no-op. */
  @Test
  void respectsExplicitActiveStorage() {
    when(settingsManager.findEffectiveSettings(project)).thenReturn("explicitly-active-storage");
    // Agent flag is irrelevant here; this row returns before it is read.

    processor.updateParameters(context);

    assertNoStorageWrites();
    assertNoBuildLogMessage();
    // The gate short-circuits before resolving the target storage.
    verify(settingsManager, never()).findSettingsWithSource(any(), anyString());
  }

  /** Row 2: built-in storage, agent not flagged -> no-op. */
  @Test
  void leavesBuiltInStorageWhenAgentNotFlagged() {
    when(settingsManager.findEffectiveSettings(project)).thenReturn(null);
    flagAgent(false);

    processor.updateParameters(context);

    assertNoStorageWrites();
    assertNoBuildLogMessage();
  }

  /** Row 3: flagged agent, built-in storage, but no target id configured -> no-op, no warning. */
  @Test
  void leavesBuiltInStorageWhenNoStorageIdConfigured() {
    when(settingsManager.findEffectiveSettings(project)).thenReturn(null);
    flagAgent(true);
    setStorageIdParam(null);

    processor.updateParameters(context);

    assertNoStorageWrites();
    assertNoBuildLogMessage();
    verify(settingsManager, never()).findSettingsWithSource(any(), anyString());
  }

  /** Row 4: flagged agent, id configured but does not resolve -> fail safe, no writes. */
  @Test
  void leavesBuiltInStorageWhenStorageIdDoesNotResolve() {
    when(settingsManager.findEffectiveSettings(project)).thenReturn(null);
    flagAgent(true);
    setStorageIdParam(STORAGE_ID);
    storageResolves(false);

    processor.updateParameters(context);

    assertNoStorageWrites();
    assertNoBuildLogMessage();
  }

  /** Row 5: flagged agent, id resolves -> apply all three writes and log to the build. */
  @Test
  void selectsS3StorageWhenFlaggedAndResolvable() {
    when(settingsManager.findEffectiveSettings(project)).thenReturn(null);
    flagAgent(true);
    setStorageIdParam(STORAGE_ID);
    storageResolves(true);

    processor.updateParameters(context);

    verify(context).addSharedParameter(ArtifactStorageSettings.STORAGE_FEATURE_ID, STORAGE_ID);
    verify(promotion).setAttribute(BuildAttributes.STORAGE_SETTINGS_REFERENCE, STORAGE_ID);
    verify(promotion).persist();
    verify(buildLog).messageAsync(anyString(), eq(Status.NORMAL), any(MessageAttrs.class));
  }

  /** Guard: a build with no build type is ignored outright. */
  @Test
  void ignoresBuildWithoutBuildType() {
    when(build.getBuildType()).thenReturn(null);

    processor.updateParameters(context);

    assertNoStorageWrites();
    verify(settingsManager, never()).findEffectiveSettings(any());
  }
}
