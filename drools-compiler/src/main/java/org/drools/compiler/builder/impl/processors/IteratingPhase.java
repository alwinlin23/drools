/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.compiler.builder.impl.processors;

import org.drools.compiler.builder.PackageRegistryManager;
import org.drools.compiler.compiler.PackageRegistry;
import org.drools.compiler.lang.descr.CompositePackageDescr;
import org.kie.internal.builder.KnowledgeBuilderResult;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Decorates a {@link CompilationPhase} via its {@link SinglePackagePhaseFactory}.
 * Iterates over a collection of {@link CompositePackageDescr}
 * and applies the decorated phase to it.
 *
 */
public class IteratingPhase implements CompilationPhase {
    private final Collection<CompositePackageDescr> packages;
    private final PackageRegistryManager pkgRegistryManager;
    private final SinglePackagePhaseFactory phaseFactory;

    private final Collection<KnowledgeBuilderResult> results = new ArrayList<>();

    public IteratingPhase(Collection<CompositePackageDescr> packages, PackageRegistryManager pkgRegistryManager, SinglePackagePhaseFactory phaseFactory) {
        this.packages = packages;
        this.pkgRegistryManager = pkgRegistryManager;
        this.phaseFactory = phaseFactory;
    }

    @Override
    public void process() {
        for (CompositePackageDescr compositePackageDescr : packages) {
            PackageRegistry packageRegistry = pkgRegistryManager.getOrCreatePackageRegistry(compositePackageDescr);
            CompilationPhase phase = phaseFactory.create(
                    packageRegistry,
                    compositePackageDescr);
            phase.process();
            results.addAll(phase.getResults());
        }
    }

    @Override
    public Collection<? extends KnowledgeBuilderResult> getResults() {
        return results;
    }
}
