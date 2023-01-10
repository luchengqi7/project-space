package org.matsim.project.drtOperationStudy.mixedCase;

import com.google.common.base.Preconditions;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter;
import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;
import org.matsim.contrib.drt.extension.preplanned.run.PreplannedDrtControlerCreator;
import org.matsim.contrib.drt.optimizer.insertion.IncrementalStopDurationEstimator;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
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

import java.nio.file.Path;

public class RunMixedCase implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--prebooked-trips", description = "path to pre-booked plans file", defaultValue = "empty")
    private String prebookedPlansFile;

    @CommandLine.Option(names = "--output", description = "path to output directory", required = true)
    private String outputDirectory;

    @CommandLine.Option(names = "--iterations", description = "number of iteration for jsprit solver", defaultValue = "1000")
    private int maxIterations;

    @CommandLine.Option(names = "--horizon", description = "horizon length of the solver", defaultValue = "1800")
    private double horizon;

    @CommandLine.Option(names = "--interval", description = "re-planning interval", defaultValue = "1800")
    private double interval;

    @CommandLine.Option(names = "--seed", defaultValue = "4711", description = "random seed for the jsprit solver")
    private long seed;

    @CommandLine.Option(names = "--prebooked-solver", defaultValue = "JSPRIT", description = "Prebooked trips solver")
    private MixedCaseModule.PrebookedRequestSolverType type;

    public static void main(String[] args) {
        new RunMixedCase().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        long startTime = System.currentTimeMillis();
        Preconditions.checkArgument(interval <= horizon, "The interval must be smaller than or equal to the horizon!");
        Population prebookedPlans;
        if (prebookedPlansFile.equals("empty")) {
            prebookedPlans = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        } else {
            prebookedPlans = PopulationUtils.readPopulation(prebookedPlansFile);
        }

        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
        DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next();
        config.controler().setOutputDirectory(outputDirectory);
        config.controler().setLastIteration(0);

        Controler controler = PreplannedDrtControlerCreator.createControler(config, false);
        controler.addOverridingModule(new DvrpModule(new DvrpBenchmarkTravelTimeModuleFixedTT(0)));
        controler.addOverridingQSimModule(new MixedCaseModule(prebookedPlans, drtConfigGroup.mode, drtConfigGroup, horizon, interval, maxIterations, false, seed, type));

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
        DrtPerformanceQuantification resultsQuantification = new DrtPerformanceQuantification();
        resultsQuantification.analyzeRollingHorizon(Path.of(outputDirectory), timeUsed, Integer.toString(maxIterations),
                Double.toString(horizon), Double.toString(interval));
        resultsQuantification.writeResultsRollingHorizon(Path.of(outputDirectory));

        // Plot DRT stopping tasks
        new DrtVehicleStoppingTaskWriter(Path.of(outputDirectory)).addingCustomizedTaskToAnalyze(WaitForStopTask.TYPE).run(WaitForStopTask.TYPE);

        return 0;
    }
}
