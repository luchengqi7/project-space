package org.matsim.project.prebookingStudy.run.routingModule;

import com.google.common.util.concurrent.Futures;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.routing.DrtRouteUpdater;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.router.DefaultMainLegRouter;
import org.matsim.contrib.util.ExecutorServiceWithResource;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.ReplanningEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.population.routes.RouteFactories;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Deprecated
public class SchoolTrafficDrtRouteUpdater implements ShutdownListener, DrtRouteUpdater {
    private final DrtConfigGroup drtCfg;
    private final Network network;
    private final Population population;
    private final ExecutorServiceWithResource<SchoolTrafficRouteCreator> executorService;

    public SchoolTrafficDrtRouteUpdater(DrtConfigGroup drtCfg, Network network, TravelTime travelTime,
                                        TravelDisutilityFactory travelDisutilityFactory, Population population, Config config) {
        this.drtCfg = drtCfg;
        this.network = network;
        this.population = population;

        LeastCostPathCalculatorFactory factory = new SpeedyALTFactory();
        // XXX uses the global.numberOfThreads, not drt.numberOfThreads, as this is executed in the replanning phase
        executorService = new ExecutorServiceWithResource<>(IntStream.range(0, config.global().getNumberOfThreads())
                .mapToObj(i -> new SchoolTrafficRouteCreator(drtCfg, network, factory, travelTime, travelDisutilityFactory))
                .collect(Collectors.toList()));
    }

    @Override
    public void notifyReplanning(ReplanningEvent event) {
        List<Future<?>> futures = new LinkedList<>();

        for (Person person : population.getPersons().values()) {
            for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                for (Leg leg : trip.getLegsOnly()) {
                    if (leg.getMode().equals(drtCfg.getMode())) {
                        futures.add(executorService.submitRunnable(
                                router -> updateDrtRoute(router, person, trip.getTripAttributes(), leg)));
                    }
                }
            }
        }

        futures.forEach(Futures::getUnchecked);
    }

    private void updateDrtRoute(DefaultMainLegRouter.RouteCreator routeCreator, Person person, Attributes tripAttributes, Leg drtLeg) {
        Link fromLink = network.getLinks().get(drtLeg.getRoute().getStartLinkId());
        Link toLink = network.getLinks().get(drtLeg.getRoute().getEndLinkId());
        RouteFactories routeFactories = population.getFactory().getRouteFactories();
        drtLeg.setRoute(routeCreator.createRoute(drtLeg.getDepartureTime().seconds(), fromLink, toLink, person,
                tripAttributes, routeFactories));
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        executorService.shutdown();
    }
}
