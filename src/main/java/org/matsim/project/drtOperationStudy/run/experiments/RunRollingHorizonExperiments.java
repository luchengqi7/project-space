package org.matsim.project.drtOperationStudy.run.experiments;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.extension.preplanned.run.PreplannedDrtControlerCreator;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.QSimScopeForkJoinPoolHolder;
import org.matsim.contrib.drt.optimizer.VehicleDataEntryFactoryImpl;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.insertion.IncrementalStopDurationEstimator;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.drt.schedule.StopDurationEstimator;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.project.drtOperationStudy.analysis.DrtPerformanceQuantification;
import org.matsim.project.drtOperationStudy.rollingHorizon.PDPTWSolverJsprit;
import org.matsim.project.drtOperationStudy.rollingHorizon.RollingHorizonDrtOptimizer;
import org.matsim.project.drtSchoolTransportStudy.run.dummyTraffic.DvrpBenchmarkTravelTimeModuleFixedTT;
import org.matsim.project.utils.LinearDrtStopDurationEstimator;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class RunRollingHorizonExperiments implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--output", description = "path to output directory", required = true)
    private String outputRootDirectory;

    @CommandLine.Option(names = "--iterations", description = "Iterations, separate by ,", defaultValue = "2000")
    private String maxIterationsString;

    @CommandLine.Option(names = "--horizon", description = "horizon lengths in second, separate by ,", defaultValue = "1800")
    private String horizonsString;

    @CommandLine.Option(names = "--interval", description = "update interval in second, separate by ,", defaultValue = "1800")
    private String intervalsString;

    @CommandLine.Option(names = "--multi-thread", defaultValue = "false", description = "enable multi-threading in JSprit to increase computation speed")
    private boolean multiThread;

    @CommandLine.Option(names = "--seed", defaultValue = "4711", description = "random seed for the jsprit solver")
    private long seed;

    private final Logger log = LogManager.getLogger(RunRollingHorizonExperiments.class);

    public static void main(String[] args) {
        new RunRollingHorizonExperiments().execute(args);
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
                    if (horizon < interval) {
                        log.warn("Horizon is smaller than the interval. Reduce interval to the horizon length!");
                        interval = horizon;
                    }

                    Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
                    MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
                    DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next();
                    config.controler().setOutputDirectory(outputDirectory);
                    config.controler().setLastIteration(0);

                    Controler controler = PreplannedDrtControlerCreator.createControler(config, false);
                    controler.addOverridingModule(new DvrpModule(new DvrpBenchmarkTravelTimeModuleFixedTT(0)));
                    // Add rolling horizon module with PDPTWSolverJsprit
                    Random random = new Random(seed);
                    var options = new PDPTWSolverJsprit.Options(iteration, multiThread, random);
                    double finalInterval = interval; // This is needed, as lambda expression only accept final variable (?)
                    controler.addOverridingQSimModule(new AbstractDvrpModeQSimModule(drtConfigGroup.getMode()) {
                        @Override
                        protected void configureQSim() {
                            addModalComponent(DrtOptimizer.class, modalProvider(getter -> new RollingHorizonDrtOptimizer(drtConfigGroup,
                                    getter.getModal(Network.class), getter.getModal(TravelTime.class),
                                    getter.getModal(TravelDisutilityFactory.class).createTravelDisutility(getter.getModal(TravelTime.class)),
                                    getter.get(MobsimTimer.class), getter.getModal(DrtTaskFactory.class), getter.get(EventsManager.class), getter.getModal(Fleet.class),
                                    getter.getModal(ScheduleTimingUpdater.class), getter.getModal(QSimScopeForkJoinPoolHolder.class).getPool(),
                                    getter.getModal(VehicleEntry.EntryFactory.class),
                                    getter.get(PDPTWSolverJsprit.class), getter.get(Population.class), horizon, finalInterval)));

                            bind(PDPTWSolverJsprit.class).toProvider(modalProvider(
                                    getter -> new PDPTWSolverJsprit(drtConfigGroup, getter.get(Network.class), options)));

                            addModalComponent(QSimScopeForkJoinPoolHolder.class,
                                    () -> new QSimScopeForkJoinPoolHolder(drtConfigGroup.numberOfThreads));
                            bindModal(VehicleEntry.EntryFactory.class).toInstance(new VehicleDataEntryFactoryImpl(drtConfigGroup));
                        }
                    });

                    // Add linear stop duration module
                    controler.addOverridingModule(new AbstractDvrpModeModule(drtConfigGroup.getMode()) {
                        @Override
                        public void install() {
                            bindModal(StopDurationEstimator.class).toInstance((vehicle, dropoffRequests, pickupRequests) -> drtConfigGroup.stopDuration * (dropoffRequests.size() + pickupRequests.size()));
                            bindModal(IncrementalStopDurationEstimator.class).toInstance(new LinearDrtStopDurationEstimator(drtConfigGroup.stopDuration));
                        }
                    });

                    controler.run();

                    // Compute time used
                    long endTime = System.currentTimeMillis();
                    long timeUsed = (endTime - startTime) / 1000;

                    // Write analysis
                    performanceQuantification.analyzeRollingHorizon(Path.of(outputDirectory), timeUsed,
                            Integer.toString(iteration), Double.toString(horizon), Double.toString(interval));
                    performanceQuantification.writeResultEntryRollingHorizon(Path.of(outputRootDirectory));
                }
            }
        }

        return 0;
    }
}
