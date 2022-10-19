package org.matsim.project.drtOperationStudy.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.extension.preplanned.optimizer.PreplannedDrtOptimizer;
import org.matsim.contrib.drt.extension.preplanned.run.PreplannedDrtControlerCreator;
import org.matsim.contrib.drt.optimizer.insertion.IncrementalStopDurationEstimator;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.schedule.StopDurationEstimator;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.otfvis.OTFVisLiveModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.project.drtOperationStudy.analysis.DrtPerformanceQuantification;
import org.matsim.project.drtOperationStudy.vrpSolver.JspritOfflineCalculator;
import org.matsim.project.utils.LinearDrtStopDurationEstimator;
import org.matsim.vis.otfvis.OTFVisConfigGroup;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;

public class RunOfflineOptimizationStrategy implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--output", description = "path to output directory", required = true)
    private String outputDirectory;

    @CommandLine.Option(names = "--iterations", description = "path to output directory", defaultValue = "1000")
    private int maxIterations;

    @CommandLine.Option(names = "--multi-thread", defaultValue = "false", description = "enable multi-threading in JSprit to increase computation speed")
    private boolean multiThread;

    @CommandLine.Option(names = "--otfvis", defaultValue = "false", description = "enable the otfvis visualiser")
    private boolean otfvis;

    private final Logger log = LogManager.getLogger(RunOfflineOptimizationStrategy.class);

    public static void main(String[] args) throws IOException {
        new RunOfflineOptimizationStrategy().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        long startTime = System.currentTimeMillis();
        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup(),
                new OTFVisConfigGroup());
        config.controler().setLastIteration(0);
        config.controler().setOutputDirectory(outputDirectory);
        for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(config).getModalElements()) {
            if (drtCfg.getRebalancingParams().isPresent()) {
                log.warn("The rebalancing parameter set is defined for drt mode: "
                        + drtCfg.getMode()
                        + ". It will be ignored. No rebalancing will happen.");
                drtCfg.removeParameterSet(drtCfg.getRebalancingParams().get());
            }
        }

        Controler controler = PreplannedDrtControlerCreator.createControler(config, otfvis);

        var options = new JspritOfflineCalculator.Options(false, true, maxIterations, multiThread);

        // compute PreplannedSchedules before starting QSim
        MultiModeDrtConfigGroup.get(config)
                .getModalElements()
                .forEach(drtConfig -> controler.addOverridingQSimModule(
                        new AbstractDvrpModeQSimModule(drtConfig.getMode()) {
                            @Override
                            protected void configureQSim() {
                                bindModal(PreplannedDrtOptimizer.PreplannedSchedules.class).toProvider(modalProvider(
                                        getter -> new JspritOfflineCalculator(drtConfig,
                                                getter.getModal(FleetSpecification.class),
                                                getter.getModal(Network.class), getter.get(Population.class),
                                                options).calculate())).asEagerSingleton();
                            }
                        }));

        if (otfvis) {
            controler.addOverridingModule(new OTFVisLiveModule());
        }

        // Add linear stop duration module
        MultiModeDrtConfigGroup multiModeDrtConfigGroup = MultiModeDrtConfigGroup.get(config);
        for (DrtConfigGroup drtCfg : multiModeDrtConfigGroup.getModalElements()) {
            // Add linear stop duration module
            controler.addOverridingModule(new AbstractDvrpModeModule(drtCfg.getMode()) {
                @Override
                public void install() {
                    bindModal(StopDurationEstimator.class).toInstance((vehicle, dropoffRequests, pickupRequests) -> drtCfg.stopDuration * (dropoffRequests.size() + pickupRequests.size()));
                    bindModal(IncrementalStopDurationEstimator.class).toInstance(new LinearDrtStopDurationEstimator(drtCfg.stopDuration));
                }
            });
        }

        controler.run();

        long endTime = System.currentTimeMillis();
        long timeUsed = (endTime - startTime) / 1000;
        // Compute the score based on the objective function of the VRP solver
        DrtPerformanceQuantification resultsQuantification = new DrtPerformanceQuantification();
        resultsQuantification.analyze(Path.of(outputDirectory), timeUsed, Integer.toString(maxIterations));
        resultsQuantification.writeResults(Path.of(outputDirectory));

        return 0;
    }
}
