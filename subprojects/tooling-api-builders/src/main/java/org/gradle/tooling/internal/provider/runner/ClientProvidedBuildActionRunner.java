/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.provider.runner;

import org.gradle.BuildResult;
import org.gradle.api.BuildCancelledException;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.invocation.Gradle;
import org.gradle.execution.ProjectConfigurer;
import org.gradle.internal.InternalBuildAdapter;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.invocation.BuildActionRunner;
import org.gradle.internal.invocation.BuildController;
import org.gradle.tooling.internal.protocol.BuildExceptionVersion1;
import org.gradle.tooling.internal.protocol.InternalBuildActionFailureException;
import org.gradle.tooling.internal.protocol.InternalBuildActionVersion2;
import org.gradle.tooling.internal.protocol.InternalBuildCancelledException;
import org.gradle.tooling.internal.provider.BuildActionResult;
import org.gradle.tooling.internal.provider.ClientProvidedBuildAction;
import org.gradle.tooling.internal.provider.serialization.PayloadSerializer;

public class ClientProvidedBuildActionRunner implements BuildActionRunner {
    @Override
    public Result run(BuildAction action, final BuildController buildController) {
        if (!(action instanceof ClientProvidedBuildAction)) {
            return Result.nothing();
        }

        GradleInternal gradle = buildController.getGradle();
        gradle.getStartParameter().setConfigureOnDemand(false);

        ClientProvidedBuildAction clientProvidedBuildAction = (ClientProvidedBuildAction) action;
        PayloadSerializer payloadSerializer = getPayloadSerializer(gradle);

        Object clientAction = payloadSerializer.deserialize(clientProvidedBuildAction.getAction());

        Throwable buildFailure = null;
        Throwable clientFailure = null;
        ResultBuildingListener listener = new ResultBuildingListener(gradle, clientAction);
        try {
            gradle.addBuildListener(listener);
            if (clientProvidedBuildAction.isRunTasks()) {
                buildController.run();
            } else {
                buildController.configure();
            }
        } catch (BuildCancelledException e) {
            buildFailure = e;
            clientFailure = new InternalBuildCancelledException(e);
        } catch (RuntimeException e) {
            buildFailure = e;
            clientFailure = new BuildExceptionVersion1(e);
        }
        if (listener.actionFailure != null && !(listener.actionFailure instanceof BuildCancelledException)) {
            clientFailure = new InternalBuildActionFailureException(listener.actionFailure);
        }

        if (buildFailure != null) {
            return Result.of(new BuildActionResult(null, payloadSerializer.serialize(clientFailure)), buildFailure);
        }
        return Result.of(listener.result);
    }

    private void forceFullConfiguration(GradleInternal gradle) {
        gradle.getServices().get(ProjectConfigurer.class).configureHierarchyFully(gradle.getRootProject());
        for (IncludedBuild includedBuild : gradle.getIncludedBuilds()) {
            GradleInternal build = ((IncludedBuildState) includedBuild).getConfiguredBuild();
            forceFullConfiguration(build);
        }
    }

    private PayloadSerializer getPayloadSerializer(GradleInternal gradle) {
        return gradle.getServices().get(PayloadSerializer.class);
    }

    private class ResultBuildingListener extends InternalBuildAdapter {
        private final GradleInternal gradle;
        private final Object clientAction;
        BuildActionResult result;
        RuntimeException actionFailure;

        ResultBuildingListener(GradleInternal gradle, Object clientAction) {
            this.gradle = gradle;
            this.clientAction = clientAction;
        }

        @Override
        public void projectsEvaluated(Gradle gradle) {
            forceFullConfiguration((GradleInternal) gradle);
        }

        @Override
        public void buildFinished(BuildResult result) {
            if (result.getFailure() != null) {
                return;
            }
            buildResult(clientAction, gradle);
        }

        @SuppressWarnings("deprecation")
        private void buildResult(Object clientAction, GradleInternal gradle) {
            DefaultBuildController internalBuildController = new DefaultBuildController(gradle);
            Object model;
            try {
                if (clientAction instanceof InternalBuildActionVersion2<?>) {
                    model = ((InternalBuildActionVersion2) clientAction).execute(internalBuildController);
                } else {
                    model = ((org.gradle.tooling.internal.protocol.InternalBuildAction) clientAction).execute(internalBuildController);
                }
            } catch (RuntimeException e) {
                actionFailure = e;
                throw e;
            }

            PayloadSerializer payloadSerializer = getPayloadSerializer(gradle);
            result = new BuildActionResult(payloadSerializer.serialize(model), null);
        }
    }
}
