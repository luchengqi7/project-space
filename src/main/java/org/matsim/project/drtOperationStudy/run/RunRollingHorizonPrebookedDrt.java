package org.matsim.project.drtOperationStudy.run;

import com.google.common.base.Preconditions;
import org.apache.log4j.Logger;
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
import org.matsim.project.drtOperationStudy.analysis.RollingHorizonResultsQuantification;
import org.matsim.project.drtOperationStudy.rollingHorizon.PDPTWSolverJsprit;
import org.matsim.project.drtOperationStudy.rollingHorizon.RollingHorizonDrtOptimizer;
import org.matsim.project.utils.LinearDrtStopDurationEstimator;
import org.matsim.project.drtSchoolTransportStudy.run.dummyTraffic.DvrpBenchmarkTravelTimeModuleFixedTT;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
        name = "rolling-horizon-test",
        description = "run simple rolling horizon DRT"
)
public class RunRollingHorizonPrebookedDrt implements MATSimAppCommand {
    private static final Logger log = Logger.getLogger(RunRollingHorizonPrebookedDrt.class);

    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--output", description = "path to output directory", required = true)
    private String outputDirectory;

    @CommandLine.Option(names = "--iterations", description = "path to output directory", defaultValue = "1000")
    private int maxIterations;

    @CommandLine.Option(names = "--horizon", description = "path to output directory", defaultValue = "1800")
    private double horizon;

    @CommandLine.Option(names = "--interval", description = "path to output directory", defaultValue = "1800")
    private double interval;

    public static void main(String[] args) {
        new RunRollingHorizonPrebookedDrt().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        long startTime = System.currentTimeMillis();
        Preconditions.checkArgument(interval <= horizon, "The interval must be smaller than or equal to the horizon!");

        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
        DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next();
        config.controler().setOutputDirectory(outputDirectory);
        config.controler().setLastIteration(0);

        // TODO Create a Rolling horizon pre-planning controller creator and move the bindings to the controller creator
        Controler controler = PreplannedDrtControlerCreator.createControler(config, false);
        controler.addOverridingModule(new DvrpModule(new DvrpBenchmarkTravelTimeModuleFixedTT(0)));
        // Add rolling horizon module with PDPTWSolverJsprit
        var options = new PDPTWSolverJsprit.Options(maxIterations, true);
        controler.addOverridingQSimModule(new AbstractDvrpModeQSimModule(drtConfigGroup.getMode()) {
            @Override
            protected void configureQSim() {
                addModalComponent(DrtOptimizer.class, modalProvider(getter -> new RollingHorizonDrtOptimizer(drtConfigGroup,
                        getter.getModal(Network.class), getter.getModal(TravelTime.class),
                        getter.getModal(TravelDisutilityFactory.class).createTravelDisutility(getter.getModal(TravelTime.class)),
                        getter.get(MobsimTimer.class), getter.getModal(DrtTaskFactory.class), getter.get(EventsManager.class), getter.getModal(Fleet.class),
                        getter.getModal(ScheduleTimingUpdater.class), getter.getModal(QSimScopeForkJoinPoolHolder.class).getPool(),
                        getter.getModal(VehicleEntry.EntryFactory.class),
                        getter.get(PDPTWSolverJsprit.class), getter.get(Population.class), horizon, interval)));

                bind(PDPTWSolverJsprit.class).toProvider(modalProvider(
                        getter -> new PDPTWSolverJsprit(drtConfigGroup, getter.get(Network.class), options)));

                addModalComponent(QSimScopeForkJoinPoolHolder.class,
                        () -> new QSimScopeForkJoinPoolHolder(drtConfigGroup.getNumberOfThreads()));
                bindModal(VehicleEntry.EntryFactory.class).toInstance(new VehicleDataEntryFactoryImpl(drtConfigGroup));
            }
        });

        // Add linear stop duration module
        controler.addOverridingModule(new AbstractDvrpModeModule(drtConfigGroup.getMode()) {
            @Override
            public void install() {
                bindModal(StopDurationEstimator.class).toInstance((vehicle, dropoffRequests, pickupRequests) -> drtConfigGroup.getStopDuration() * (dropoffRequests.size() + pickupRequests.size()));
                bindModal(IncrementalStopDurationEstimator.class).toInstance(new LinearDrtStopDurationEstimator(drtConfigGroup.getStopDuration()));
            }
        });

        controler.run();

        // Compute time used
        long endTime = System.currentTimeMillis();
        long timeUsed = (endTime - startTime) / 1000;
        // Compute the score based on the objective function of the VRP solver
        RollingHorizonResultsQuantification resultsQuantification = new RollingHorizonResultsQuantification();
        resultsQuantification.analyze(Path.of(outputDirectory), timeUsed);
        resultsQuantification.writeResults(Path.of(outputDirectory));

        return 0;
    }
}
