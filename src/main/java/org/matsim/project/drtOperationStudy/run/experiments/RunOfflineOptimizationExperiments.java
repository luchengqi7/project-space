package org.matsim.project.drtOperationStudy.run.experiments;

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
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.project.drtOperationStudy.analysis.DrtPerformanceQuantification;
import org.matsim.project.drtOperationStudy.vrpSolver.JspritOfflineCalculatorForExperiments;
import org.matsim.project.utils.LinearDrtStopDurationEstimator;
import org.matsim.vis.otfvis.OTFVisConfigGroup;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

public class RunOfflineOptimizationExperiments implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--output", description = "path to output directory", required = true)
    private String outputRootDirectory;

    @CommandLine.Option(names = "--iterations", description = "Number of iterations to run, separate with ,", defaultValue = "1000")
    private String iterationsToRun;

    @CommandLine.Option(names = "--multi-thread", defaultValue = "false", description = "enable multi-threading in JSprit to increase computation speed")
    private boolean multiThread;

    @CommandLine.Option(names = "--seed", defaultValue = "4711", description = "random seed for the jsprit solver")
    private long seed;

    private final Logger log = LogManager.getLogger(RunOfflineOptimizationExperiments.class);

    public static void main(String[] args) throws IOException {
        new RunOfflineOptimizationExperiments().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        JspritOfflineCalculatorForExperiments.VrpCommonPart vrpCommonPart = new JspritOfflineCalculatorForExperiments.VrpCommonPart();
        boolean initialized = false;

        for (String iterationString : iterationsToRun.split(",")) {
            long startTime = System.currentTimeMillis();

            int maxIterations = Integer.parseInt(iterationString);
            Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup(),
                    new OTFVisConfigGroup());
            config.controler().setLastIteration(0);
            String outputDirectory = outputRootDirectory + "/" + iterationString;
            config.controler().setOutputDirectory(outputDirectory);
            for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(config).getModalElements()) {
                if (drtCfg.getRebalancingParams().isPresent()) {
                    log.warn("The rebalancing parameter set is defined for drt mode: "
                            + drtCfg.getMode()
                            + ". It will be ignored. No rebalancing will happen.");
                    drtCfg.removeParameterSet(drtCfg.getRebalancingParams().get());
                }
            }
            Controler controler = PreplannedDrtControlerCreator.createControler(config, false);

            Random random = new Random(seed);
            var options = new JspritOfflineCalculatorForExperiments.Options(true, maxIterations, multiThread, random);

            // compute PreplannedSchedules before starting QSim
            MultiModeDrtConfigGroup.get(config)
                    .getModalElements()
                    .forEach(drtConfig -> controler.addOverridingQSimModule(
                            new AbstractDvrpModeQSimModule(drtConfig.getMode()) {
                                @Override
                                protected void configureQSim() {
                                    bindModal(PreplannedDrtOptimizer.PreplannedSchedules.class).toProvider(modalProvider(
                                            getter -> new JspritOfflineCalculatorForExperiments(drtConfig,
                                                    getter.getModal(FleetSpecification.class),
                                                    getter.getModal(Network.class), getter.get(Population.class),
                                                    options).calculate(vrpCommonPart))).asEagerSingleton();
                                }
                            }));

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

            // Analyze the result
            DrtPerformanceQuantification resultsQuantification = new DrtPerformanceQuantification();
            long timeUsed = (endTime - startTime) / 1000;

            if (initialized) {
                timeUsed += vrpCommonPart.vrpCommonPartRecord.initializationTime();
            } else {
                resultsQuantification.writeTitle(Path.of(outputRootDirectory));
                initialized = true;
            }

            resultsQuantification.analyze(Path.of(outputDirectory), timeUsed, Integer.toString(maxIterations));
            resultsQuantification.writeResultEntry(Path.of(outputRootDirectory));
        }

        return 0;
    }
}
