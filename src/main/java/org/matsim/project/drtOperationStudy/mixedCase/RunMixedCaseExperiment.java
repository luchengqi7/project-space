package org.matsim.project.drtOperationStudy.mixedCase;

import com.google.common.base.Preconditions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter;
import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;
import org.matsim.contrib.drt.extension.preplanned.run.PreplannedDrtControlerCreator;
import org.matsim.contrib.drt.optimizer.insertion.IncrementalStopDurationEstimator;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.schedule.StopDurationEstimator;
import org.matsim.contrib.dvrp.router.DvrpModeRoutingNetworkModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.project.drtOperationStudy.analysis.DrtPerformanceQuantification;
import org.matsim.project.drtSchoolTransportStudy.run.dummyTraffic.DvrpBenchmarkTravelTimeModuleFixedTT;
import org.matsim.project.utils.LinearDrtStopDurationEstimator;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

public class RunMixedCaseExperiment implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--prebooked-trips", description = "path to pre-booked plans file", required = true)
    private String prebookedPlansFile;

    @CommandLine.Option(names = "--output", description = "path to output directory", required = true)
    private String outputRootDirectory;

    @CommandLine.Option(names = "--iterations", description = "Iterations, separate by ,", defaultValue = "2000")
    private String maxIterationsString;

    @CommandLine.Option(names = "--horizon", description = "horizon lengths in second, separate by ,", defaultValue = "1800")
    private String horizonsString;

    @CommandLine.Option(names = "--interval", description = "update interval in second, separate by ,", defaultValue = "1800")
    private String intervalsString;

    @CommandLine.Option(names = "--seed", defaultValue = "4711", description = "random seed for the jsprit solver")
    private long seed;

    @CommandLine.Option(names = "--prebooked-solver", defaultValue = "JSPRIT", description = "Prebooked trips solver")
    private MixedCaseModule.PrebookedRequestSolverType type;

    private final Logger log = LogManager.getLogger(RunMixedCaseExperiment.class);

    public static void main(String[] args) {
        new RunMixedCaseExperiment().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        String[] iterationsToRun = maxIterationsString.split(",");
        String[] horizons = horizonsString.split(",");
        String[] intervals = intervalsString.split(",");

        DrtPerformanceQuantification performanceQuantification = new DrtPerformanceQuantification();
        if (!Files.exists(Path.of(outputRootDirectory))) {
            Files.createDirectory(Path.of(outputRootDirectory));
        }
        performanceQuantification.writeTitleForRollingHorizon(Path.of(outputRootDirectory));

        for (String iterationString : iterationsToRun) {
            for (String horizonString : horizons) {
                for (String intervalString : intervals) {
                    long startTime = System.currentTimeMillis();

                    String outputDirectory = outputRootDirectory + "/horizon_" + horizonString + "-interval_" +
                            intervalString + "-iterations_" + iterationString;
                    double horizon = Double.parseDouble(horizonString);
                    double interval = Double.parseDouble(intervalString);
                    int iteration = Integer.parseInt(iterationString);

                    Preconditions.checkArgument(interval <= horizon, "The interval must be smaller than or equal to the horizon!");
                    Population prebookedPlans = PopulationUtils.readPopulation(prebookedPlansFile);

                    Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
                    MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
                    DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next();
                    config.controler().setOutputDirectory(outputDirectory);
                    config.controler().setLastIteration(0);

                    Controler controler = PreplannedDrtControlerCreator.createControler(config, false);
                    controler.addOverridingModule(new DvrpModule(new DvrpBenchmarkTravelTimeModuleFixedTT(0)));
                    controler.addOverridingQSimModule(new MixedCaseModule(prebookedPlans, drtConfigGroup.mode, drtConfigGroup, horizon, interval, iteration, false, seed, type));

                    // Add linear stop duration module
                    controler.addOverridingModule(new AbstractDvrpModeModule(drtConfigGroup.getMode()) {
                        @Override
                        public void install() {
                            install(new DvrpModeRoutingNetworkModule(getMode(), drtConfigGroup.useModeFilteredSubnetwork));
                            bindModal(StopDurationEstimator.class).toInstance((vehicle, dropOffRequests, pickupRequests) -> drtConfigGroup.stopDuration * (dropOffRequests.size() + pickupRequests.size()));
                            bindModal(IncrementalStopDurationEstimator.class).toInstance(new LinearDrtStopDurationEstimator(drtConfigGroup.stopDuration));
                        }
                    });

                    controler.run();

                    // Compute time used
                    long endTime = System.currentTimeMillis();
                    long timeUsed = (endTime - startTime) / 1000;
                    // Compute the score based on the objective function of the VRP solver
                    performanceQuantification.analyzeRollingHorizon(Path.of(outputDirectory), timeUsed, Integer.toString(iteration),
                            Double.toString(horizon), Double.toString(interval));
                    performanceQuantification.writeResultEntryRollingHorizon(Path.of(outputRootDirectory));

                    // Plot DRT stopping tasks
                    new DrtVehicleStoppingTaskWriter(Path.of(outputDirectory)).addingCustomizedTaskToAnalyze(WaitForStopTask.TYPE).run(WaitForStopTask.TYPE);
                }
            }
        }
        return 0;
    }
}
