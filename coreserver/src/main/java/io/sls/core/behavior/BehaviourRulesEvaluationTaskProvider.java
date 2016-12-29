/*
 * Copyright 2014 Blazebit.
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

package io.sls.core.behavior;

import io.sls.lifecycle.ILifecycleTask;
import io.sls.lifecycle.spi.ILifecycleTaskProviderSpi;

/**
 * @author Moritz Becker (moritz.becker@gmx.at)
 * @since 1.2
 */
public class BehaviourRulesEvaluationTaskProvider implements ILifecycleTaskProviderSpi {
    @Override
    public String getLifecycleTaskId() {
        return "io.sls.behavior";
    }

    @Override
    public Class<? extends ILifecycleTask> getLifecycleTaskClass() {
        return BehaviorRulesEvaluationTask.class;
    }
}
