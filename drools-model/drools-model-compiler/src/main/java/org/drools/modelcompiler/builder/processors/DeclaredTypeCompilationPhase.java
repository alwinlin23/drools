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

package org.drools.modelcompiler.builder.processors;

import org.drools.compiler.builder.PackageRegistryManager;
import org.drools.compiler.builder.impl.BuildResultCollector;
import org.drools.compiler.builder.impl.BuildResultCollectorImpl;
import org.drools.compiler.builder.impl.KnowledgeBuilderConfigurationImpl;
import org.drools.compiler.builder.impl.processors.CompilationPhase;
import org.drools.compiler.builder.impl.processors.SinglePackagePhaseFactory;
import org.drools.compiler.builder.impl.processors.IteratingPhase;
import org.drools.compiler.lang.descr.CompositePackageDescr;
import org.drools.modelcompiler.builder.CanonicalModelBuildContext;
import org.drools.modelcompiler.builder.PackageModelManager;
import org.drools.modelcompiler.builder.generator.declaredtype.POJOGenerator;
import org.kie.internal.builder.KnowledgeBuilderResult;

import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;

public class DeclaredTypeCompilationPhase implements CompilationPhase {

    private final PackageModelManager packageModelManager;
    private final PackageRegistryManager pkgRegistryManager;
    private final CanonicalModelBuildContext buildContext;
    private final KnowledgeBuilderConfigurationImpl buildConfiguration;
    private final Collection<CompositePackageDescr> packages;
    private final BuildResultCollector results;

    public DeclaredTypeCompilationPhase(
            PackageModelManager packageModelManager,
            PackageRegistryManager pkgRegistryManager,
            CanonicalModelBuildContext buildContext,
            KnowledgeBuilderConfigurationImpl buildConfiguration,
            Collection<CompositePackageDescr> packages) {
        this.packageModelManager = packageModelManager;
        this.pkgRegistryManager = pkgRegistryManager;
        this.buildContext = buildContext;
        this.buildConfiguration = buildConfiguration;
        this.packages = packages;
        this.results = new BuildResultCollectorImpl();
    }

    @Override
    public void process() {
        List<CompilationPhase> phases = asList(
                iteratingPhase((reg, acc) -> new TypeDeclarationRegistrationPhase(reg, acc, pkgRegistryManager)),
                iteratingPhase((reg, acc) ->
                        new POJOGenerator(reg.getPackage(), acc, packageModelManager.getPackageModel(acc, reg, reg.getPackage().getName()))),
                new GeneratedPojoCompilationPhase(
                        packageModelManager, buildContext, buildConfiguration.getClassLoader()),
                new PojoStoragePhase(buildContext, pkgRegistryManager, packages)
        );

        for (CompilationPhase phase : phases) {
            phase.process();
            this.results.addAll(phase.getResults());
            // should not stop on error: continue.
        }

    }

    private IteratingPhase iteratingPhase(SinglePackagePhaseFactory phaseFactory) {
        return new IteratingPhase(packages, pkgRegistryManager, phaseFactory);
    }

    @Override
    public Collection<? extends KnowledgeBuilderResult> getResults() {
        return results.getAllResults();
    }

}
