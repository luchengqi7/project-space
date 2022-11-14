package org.matsim.project.drtOperationStudy.mixedCase;

import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.QSimScopeForkJoinPoolHolder;
import org.matsim.contrib.drt.optimizer.VehicleDataEntryFactoryImpl;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;

import java.util.Random;

public class MixedCaseModule extends AbstractDvrpModeQSimModule {
    private final Population prebookedPlans;
    private final DrtConfigGroup drtConfigGroup;
    private final double horizon;
    private final double interval;
    private final int maxIteration;
    private final boolean multiThread;
    private final long seed;

    public MixedCaseModule(Population prebookedPlans, String mode, DrtConfigGroup drtConfigGroup, double horizon, double interval, int maxIterations, boolean multiThread, long seed) {
        super(mode);
        this.prebookedPlans = prebookedPlans;
        this.drtConfigGroup = drtConfigGroup;
        this.horizon = horizon;
        this.interval = interval;
        this.maxIteration = maxIterations;
        this.multiThread = multiThread;
        this.seed = seed;
    }

    @Override
    protected void configureQSim() {
        addModalComponent(DrtOptimizer.class, this.modalProvider((getter) -> new MixedCaseDrtOptimizer(getter.getModal(Network.class), getter.getModal(TravelTime.class),
                getter.get(MobsimTimer.class), getter.getModal(DrtTaskFactory.class),
                getter.get(EventsManager.class), getter.getModal(ScheduleTimingUpdater.class),
                getter.getModal(TravelDisutilityFactory.class).createTravelDisutility(getter.getModal(TravelTime.class)),
                drtConfigGroup.stopDuration, drtConfigGroup.getMode(), drtConfigGroup, getter.getModal(Fleet.class),
                getter.getModal(QSimScopeForkJoinPoolHolder.class).getPool(),
                getter.getModal(VehicleEntry.EntryFactory.class),
                getter.get(PrebookedRequestsSolverJsprit.class),
                getter.get(SimpleOnlineInserter.class),
                getter.get(Population.class),
                horizon, interval, prebookedPlans)));

        bind(SimpleOnlineInserter.class).toProvider(modalProvider(
                getter -> new SimpleOnlineInserter(getter.getModal(Network.class), drtConfigGroup)));

        bind(PrebookedRequestsSolverJsprit.class).toProvider(modalProvider(
                getter -> new PrebookedRequestsSolverJsprit(
                        new PrebookedRequestsSolverJsprit.Options(maxIteration, multiThread, new Random(seed)),
                        drtConfigGroup, getter.getModal(Network.class))));

        addModalComponent(QSimScopeForkJoinPoolHolder.class,
                () -> new QSimScopeForkJoinPoolHolder(drtConfigGroup.numberOfThreads));
        bindModal(VehicleEntry.EntryFactory.class).toInstance(new VehicleDataEntryFactoryImpl(drtConfigGroup));
    }
}
