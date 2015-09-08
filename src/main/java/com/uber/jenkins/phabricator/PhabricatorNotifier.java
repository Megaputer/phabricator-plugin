// Copyright (c) 2015 Uber Technologies, Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package com.uber.jenkins.phabricator;

import com.uber.jenkins.phabricator.conduit.ConduitAPIClient;
import com.uber.jenkins.phabricator.conduit.ConduitAPIException;
import com.uber.jenkins.phabricator.conduit.Differential;
import com.uber.jenkins.phabricator.conduit.DifferentialClient;
import com.uber.jenkins.phabricator.coverage.CodeCoverageMetrics;
import com.uber.jenkins.phabricator.coverage.CoverageProvider;
import com.uber.jenkins.phabricator.credentials.ConduitCredentials;
import com.uber.jenkins.phabricator.provider.BaseProvider;
import com.uber.jenkins.phabricator.provider.Provider;
import com.uber.jenkins.phabricator.tasks.NonDifferentialBuildTask;
import com.uber.jenkins.phabricator.uberalls.UberallsClient;
import com.uber.jenkins.phabricator.unit.UnitTestProvider;
import com.uber.jenkins.phabricator.utils.CommonUtils;
import com.uber.jenkins.phabricator.utils.Logger;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Job;
import hudson.model.Result;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;

public class PhabricatorNotifier extends Notifier {
    public static final String COBERTURA_CLASS_NAME = "com.uber.jenkins.phabricator.coverage.CoberturaCoverageProvider";
    private static final String JUNIT_CLASS_NAME = "com.uber.jenkins.phabricator.unit.JUnitTestProvider";
    private static final String UBERALLS_TAG = "uberalls";
    private static final String CONDUIT_TAG = "conduit";
    private static final String JUNIT_PLUGIN_NAME = "junit";
    private static final String UNIT_TAG = "unit-results";
    // Post a comment on success. Useful for lengthy builds.
    private final boolean commentOnSuccess;
    private final boolean uberallsEnabled;
    private final boolean commentWithConsoleLinkOnFailure;
    private final boolean preserveFormatting;
    private final String commentFile;
    private final String commentSize;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public PhabricatorNotifier(boolean commentOnSuccess, boolean uberallsEnabled, boolean preserveFormatting,
                               String commentFile, String commentSize, boolean commentWithConsoleLinkOnFailure) {
        this.commentOnSuccess = commentOnSuccess;
        this.uberallsEnabled = uberallsEnabled;
        this.commentFile = commentFile;
        this.commentSize = commentSize;
        this.preserveFormatting = preserveFormatting;
        this.commentWithConsoleLinkOnFailure = commentWithConsoleLinkOnFailure;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public final boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher,
                                 final BuildListener listener) throws InterruptedException, IOException {
        EnvVars environment = build.getEnvironment(listener);
        Logger logger = new Logger(listener.getLogger());

        final CoverageProvider coverageProvider = getUberallsCoverage(build, listener);
        CodeCoverageMetrics coverageResult = null;
        if (coverageProvider != null) {
            coverageResult = coverageProvider.getMetrics();
        }

        final String branch = environment.get("GIT_BRANCH");
        final UberallsClient uberallsClient = new UberallsClient(
                getDescriptor().getUberallsURL(),
                logger,
                environment.get("GIT_URL"),
                branch
        );
        final boolean needsDecoration = build.getActions(PhabricatorPostbuildAction.class).size() == 0;

        final String diffID = environment.get(PhabricatorPlugin.DIFFERENTIAL_ID_FIELD);

        // Handle non-differential build invocations.
        if (CommonUtils.isBlank(diffID)) {
            if (needsDecoration) {
                build.addAction(PhabricatorPostbuildAction.createShortText(branch, null));
            }

            NonDifferentialBuildTask nonDifferentialBuildTask = new NonDifferentialBuildTask(
                    logger,
                    uberallsClient,
                    coverageResult,
                    uberallsEnabled,
                    environment.get("GIT_COMMIT")
            );

            // Ignore the result.
            nonDifferentialBuildTask.run();
            return true;
        }

        ConduitAPIClient conduitClient;
        try {
            conduitClient = getConduitClient(build.getParent());
        } catch (ConduitAPIException e) {
            e.printStackTrace(logger.getStream());
            logger.warn(CONDUIT_TAG, e.getMessage());
            return false;
        }

        DifferentialClient diffClient = new DifferentialClient(diffID, conduitClient);
        Differential diff;
        try {
            diff = new Differential(diffClient.fetchDiff());
        } catch (ConduitAPIException e) {
            logger.info(CONDUIT_TAG, "Unable to fetch differential");
            return true;
        }

        if (needsDecoration) {
            diff.decorate(build, this.getPhabricatorURL(build.getParent()));
        }

        BuildResultProcessor resultProcessor = new BuildResultProcessor(
                logger,
                build,
                diff,
                diffClient,
                environment.get(PhabricatorPlugin.PHID_FIELD),
                coverageResult,
                environment.get("BUILD_URL"),
                preserveFormatting
        );

        resultProcessor.processParentCoverage(uberallsClient);

        // Add in comments about the build result
        resultProcessor.processBuildResult(commentOnSuccess, commentWithConsoleLinkOnFailure);

        // Process unit tests results to send to Harbormaster
        resultProcessor.processUnitResults(getUnitProvider(build, listener));

        // Read coverage data to send to Harbormaster
        resultProcessor.processCoverage(coverageProvider);

        // Fail the build if we can't report to Harbormaster
        if (!resultProcessor.processHarbormaster()) {
            return false;
        }

        resultProcessor.processRemoteComment(commentFile, commentSize);

        resultProcessor.sendComment(commentWithConsoleLinkOnFailure);

        return true;
    }

    private ConduitAPIClient getConduitClient(Job owner) throws ConduitAPIException {
        ConduitCredentials credentials = getConduitCredentials(owner);
        if (credentials == null) {
            throw new ConduitAPIException("No credentials configured for conduit");
        }
        return new ConduitAPIClient(credentials.getUrl(), credentials.getToken().getPlainText());
    }

    /**
     * Get the cobertura coverage for the build
     * @param build The current build
     * @param listener The build listener
     * @return The current cobertura coverage, if any
     */
    private CoverageProvider getUberallsCoverage(AbstractBuild build, BuildListener listener) {
        if (!build.getResult().isBetterOrEqualTo(Result.UNSTABLE) || !uberallsEnabled) {
            return null;
        }

        Logger logger = new Logger(listener.getLogger());

        Provider<CoverageProvider> provider = new BaseProvider<CoverageProvider>(
                Jenkins.getInstance(),
                "cobertura",
                logger
        );

        if (!provider.isAvailable()) {
            logger.info(UBERALLS_TAG, "Cobertura plugin not installed, skipping.");
            return null;
        }

        CoverageProvider coverage = provider.getInstance(COBERTURA_CLASS_NAME);
        if (coverage == null) {
            logger.warn(UBERALLS_TAG, "Unable to load Cobertura coverage provider. Something is really wrong.");
            return null;
        }

        coverage.setBuild(build);
        if (coverage.hasCoverage()) {
            return coverage;
        } else {
            logger.info(UBERALLS_TAG, "No cobertura results found");
            return null;
        }
    }

    private UnitTestProvider getUnitProvider(AbstractBuild build, BuildListener listener) {
        Logger logger = new Logger(listener.getLogger());

        Provider<UnitTestProvider> provider = new BaseProvider<UnitTestProvider>(
                Jenkins.getInstance(),
                JUNIT_PLUGIN_NAME,
                logger
        );

        if (!provider.isAvailable()) {
            logger.info(UNIT_TAG, "JUnit plugin not installed, skipping unit test reporting.");
        }

        UnitTestProvider unitProvider = provider.getInstance(JUNIT_CLASS_NAME);
        if (unitProvider == null) {
            logger.warn(UNIT_TAG, "Unable to load JUnit coverage provider. Something is very wrong.");
            return null;
        }
        unitProvider.setBuild(build);
        return unitProvider;
    }

    @SuppressWarnings("UnusedDeclaration")
    public boolean isCommentOnSuccess() {
        return commentOnSuccess;
    }

    @SuppressWarnings("UnusedDeclaration")
    public boolean isUberallsEnabled() {
        return uberallsEnabled;
    }

    @SuppressWarnings("UnusedDeclaration")
    public boolean isCommentWithConsoleLinkOnFailure() {
        return commentWithConsoleLinkOnFailure;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getCommentFile() {
        return commentFile;
    }

    @SuppressWarnings("UnusedDeclaration")
    public boolean isPreserveFormatting() {
        return preserveFormatting;
    }

    private ConduitCredentials getConduitCredentials(Job owner) {
        return getDescriptor().getCredentials(owner);
    }

    private String getPhabricatorURL(Job owner) {
        ConduitCredentials credentials = getDescriptor().getCredentials(owner);
        if (credentials != null) {
            return credentials.getUrl();
        }
        return null;
    }

    // Overridden for better type safety.
    @Override
    public PhabricatorNotifierDescriptor getDescriptor() {
        return (PhabricatorNotifierDescriptor) super.getDescriptor();
    }
}
