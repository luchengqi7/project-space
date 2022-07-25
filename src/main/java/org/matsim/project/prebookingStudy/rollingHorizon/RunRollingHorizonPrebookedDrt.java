package org.matsim.project.prebookingStudy.rollingHorizon;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.extension.preplanned.run.PreplannedDrtControlerCreator;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.QSimScopeForkJoinPoolHolder;
import org.matsim.contrib.drt.optimizer.VehicleDataEntryFactoryImpl;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.Fleet;
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
import org.matsim.project.prebookingStudy.run.dummyTraffic.DvrpBenchmarkTravelTimeModuleFixedTT;
import picocli.CommandLine;

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

    public static void main(String[] args) {
        new RunRollingHorizonPrebookedDrt().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
        DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next();
        config.controler().setOutputDirectory(outputDirectory);
        config.controler().setLastIteration(0);

        Controler controler = PreplannedDrtControlerCreator.createControler(config, false);
        controler.addOverridingModule(new DvrpModule(new DvrpBenchmarkTravelTimeModuleFixedTT(0)));

        var options = new PDPTWSolverJsprit.Options(maxIterations, true);

        // TODO move these bindings to one module
        controler.addOverridingQSimModule(new AbstractDvrpModeQSimModule(drtConfigGroup.getMode()) {
            @Override
            protected void configureQSim() {
                addModalComponent(DrtOptimizer.class, modalProvider(getter -> new RollingHorizonDrtOptimizer(drtConfigGroup,
                        getter.getModal(Network.class), getter.getModal(TravelTime.class),
                        getter.getModal(TravelDisutilityFactory.class).createTravelDisutility(getter.getModal(TravelTime.class)),
                        getter.get(MobsimTimer.class), getter.getModal(DrtTaskFactory.class), getter.get(EventsManager.class), getter.getModal(Fleet.class),
                        getter.getModal(ScheduleTimingUpdater.class), getter.getModal(QSimScopeForkJoinPoolHolder.class).getPool(),
                        getter.getModal(VehicleEntry.EntryFactory.class),
                        getter.get(PDPTWSolverJsprit.class), getter.get(Population.class))));

                bind(PDPTWSolverJsprit.class).toProvider(modalProvider(
                        getter -> new PDPTWSolverJsprit(drtConfigGroup, getter.get(Network.class), options)));

                addModalComponent(QSimScopeForkJoinPoolHolder.class,
                        () -> new QSimScopeForkJoinPoolHolder(drtConfigGroup.getNumberOfThreads()));
                bindModal(VehicleEntry.EntryFactory.class).toInstance(new VehicleDataEntryFactoryImpl(drtConfigGroup));
            }
        });

        controler.run();
        return 0;
    }
}
