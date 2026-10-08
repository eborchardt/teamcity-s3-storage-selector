package com.eborchardt.teamcity.artifacts.s3selector;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.artifacts.ArtifactStorageSettings;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.BuildAttributes;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.BuildStartContext;
import jetbrains.buildServer.serverSide.BuildStartContextProcessor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.buildLog.BuildLog;
import jetbrains.buildServer.serverSide.buildLog.MessageAttrs;
import jetbrains.buildServer.serverSide.storage.ArtifactsStorageSettingsManager;
import org.jetbrains.annotations.NotNull;

/**
 * Redirects a build's artifact publishing to a pre-configured S3 artifacts storage when the build
 * is assigned to an agent that opts in via a configuration parameter.
 *
 * <p>The S3 storage is expected to be defined on the project (or an ancestor) but left
 * <b>inactive</b>, so normal builds keep publishing to the built-in TeamCity server storage. This
 * server-side {@link BuildStartContextProcessor} selects the inactive S3 storage for a single build
 * when, and only when:
 * <ol>
 *   <li>the build's project has no explicitly active storage of its own or inherited
 *       ({@link ArtifactsStorageSettingsManager#findEffectiveSettings(SProject)} is {@code null} —
 *       an explicit choice always wins and is never overridden);</li>
 *   <li>the assigned agent carries the configuration parameter
 *       {@value #AGENT_USE_S3_FLAG}{@code =true} (from {@code buildAgent.properties}); and</li>
 *   <li>the project parameter {@value #S3_STORAGE_ID_PARAM} names a storage feature id that
 *       actually resolves for the build's project hierarchy.</li>
 * </ol>
 *
 * <p>If the flag is on but no storage id is configured, or the configured id does not resolve, the
 * processor is a no-op and the build keeps the built-in storage (fail safe). Only the unresolved
 * case is a misconfiguration and is logged as a warning.
 */
public class SelectArtifactsStorageStartBuildProcessor implements BuildStartContextProcessor {

  // Log under jetbrains.buildServer.* so the lines reach teamcity-server.log at INFO. TeamCity's
  // log4j routes the `jetbrains.buildServer` logger (level INFO) to the server log file; a plugin's
  // own package only hits the root console appender, which is WARN-thresholded and drops INFO. Do
  // NOT use the ARTIFACTS category: it is level=ERROR with additivity=false and goes solely to
  // teamcity-artifacts.log. A fresh `jetbrains.buildServer.s3selector.*` child keeps the channel
  // dedicated and greppable while inheriting the INFO/server-log routing.
  private static final Logger LOG = Logger.getInstance(
      "jetbrains.buildServer.s3selector." + SelectArtifactsStorageStartBuildProcessor.class.getSimpleName());

  /**
   * Agent configuration parameter (set in {@code buildAgent.properties}) that opts the agent into
   * S3 artifact storage. Absent or any non-{@code true} value is treated as {@code false}.
   */
  static final String AGENT_USE_S3_FLAG = "teamcity.artifacts.useS3Storage";

  /**
   * Project-level configuration parameter naming the id of the (inactive) S3 storage feature to
   * redirect flagged builds to.
   */
  static final String S3_STORAGE_ID_PARAM = "teamcity.artifacts.s3StorageId";

  @NotNull private final ArtifactsStorageSettingsManager mySettingsManager;

  public SelectArtifactsStorageStartBuildProcessor(
      @NotNull final ArtifactsStorageSettingsManager settingsManager) {
    mySettingsManager = settingsManager;
  }

  @Override
  public void updateParameters(@NotNull final BuildStartContext context) {
    final SRunningBuild build = context.getBuild();
    final SBuildType buildType = build.getBuildType();
    if (buildType == null) {
      return;
    }
    final SProject project = buildType.getProject();

    // (1) An explicit active storage — on this project or inherited from an ancestor — always wins.
    // The feature is purely additive: it only ever redirects builds that would otherwise use the
    // built-in server storage. findEffectiveSettings returns null exactly in that built-in case.
    final String effectiveStorageId = mySettingsManager.findEffectiveSettings(project);
    if (effectiveStorageId != null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Build " + build.getBuildId() + " already resolves to active artifacts storage '"
            + effectiveStorageId + "'; leaving it unchanged.");
      }
      return;
    }

    // (2) Only redirect when the assigned agent is flagged for S3 storage.
    if (!isAgentFlaggedForS3(build)) {
      return;
    }

    // (3) Resolve which S3 storage to use from the project-level parameter.
    final BuildPromotionEx promotion = (BuildPromotionEx) build.getBuildPromotion();
    final String s3StorageId = promotion.getParameterValue(S3_STORAGE_ID_PARAM);
    if (s3StorageId == null || s3StorageId.isEmpty()) {
      // Flagged agent but no target storage configured: keep the built-in storage. This is a
      // deliberate configuration (not every flagged project must use S3), so no warning.
      if (LOG.isDebugEnabled()) {
        LOG.debug("Agent for build " + build.getBuildId() + " is flagged via " + AGENT_USE_S3_FLAG
            + " but no " + S3_STORAGE_ID_PARAM + " is set; keeping built-in storage.");
      }
      return;
    }

    // (4) Validate that the id resolves in this project's hierarchy before applying it. A stale or
    // out-of-scope id would otherwise point the build at a non-existent storage and break artifact
    // publishing; failing safe to the built-in storage is strictly better.
    if (mySettingsManager.findSettingsWithSource(project, s3StorageId) == null) {
      LOG.warn("Agent for build " + build.getBuildId() + " is flagged via " + AGENT_USE_S3_FLAG
          + ", but the S3 storage id '" + s3StorageId + "' from " + S3_STORAGE_ID_PARAM
          + " does not resolve for project '" + project.getExternalId()
          + "'. Keeping the built-in storage.");
      return;
    }

    // (5) Apply. Two channels, both deliberate:
    //   - the shared build parameter is what the agent-side artifact publisher reads;
    //   - the promotion attribute is the server-side storage reference, persisted with the build.
    // STORAGE_SETTINGS_REFERENCE is defined as == STORAGE_FEATURE_ID, so both carry the same key.
    context.addSharedParameter(ArtifactStorageSettings.STORAGE_FEATURE_ID, s3StorageId);
    promotion.setAttribute(BuildAttributes.STORAGE_SETTINGS_REFERENCE, s3StorageId);
    promotion.persist();

    final String message = "Publishing artifacts to S3 storage '" + s3StorageId
        + "' (agent flagged via " + AGENT_USE_S3_FLAG + ").";
    LOG.info("Build " + build.getBuildId() + ": " + message);
    logToBuild(build, message);
  }

  private boolean isAgentFlaggedForS3(@NotNull final SRunningBuild build) {
    final String flag = build.getAgent().getConfigurationParameters().get(AGENT_USE_S3_FLAG);
    // null / "" / "false" / anything non-"true" -> false; only "true" (any case) opts in.
    return Boolean.parseBoolean(flag);
  }

  /**
   * Writes a user-facing line to the build log. Best effort: a failure here must never prevent the
   * build from starting, so it is swallowed (mirroring how the platform itself guards build-log
   * writes).
   */
  private void logToBuild(@NotNull final SRunningBuild build, @NotNull final String message) {
    try {
      final BuildLog buildLog = build.getBuildLog();
      buildLog.messageAsync(message, Status.NORMAL, MessageAttrs.serverMessage());
    } catch (final Exception e) {
      LOG.warnAndDebugDetails(
          "Could not write the S3 storage selection message to the build log", e);
    }
  }
}
