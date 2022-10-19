package org.matsim.project.drtSchoolTransportStudy.run.routingModule;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.DefaultMainLegRouter;
import org.matsim.contrib.dvrp.run.DvrpMode;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.core.modal.ModalProviders;
import org.matsim.core.population.routes.RouteFactories;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.utils.objectattributes.attributable.Attributes;

public class SchoolTrafficRouteCreator implements DefaultMainLegRouter.RouteCreator {
    private final DrtConfigGroup drtCfg;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;

    public SchoolTrafficRouteCreator(DrtConfigGroup drtCfg, Network modalNetwork,
                                     LeastCostPathCalculatorFactory leastCostPathCalculatorFactory,
                                     TravelTime travelTime, TravelDisutilityFactory travelDisutilityFactory) {
        this.drtCfg = drtCfg;
        this.travelTime = travelTime;
        router = leastCostPathCalculatorFactory.createPathCalculator(modalNetwork,
                travelDisutilityFactory.createTravelDisutility(travelTime), travelTime);
    }

    @Override
    public Route createRoute(double departureTime, Link accessActLink, Link egressActLink, Person person,
                             Attributes tripAttributes, RouteFactories routeFactories) {
        VrpPathWithTravelData unsharedPath = VrpPaths.calcAndCreatePath(accessActLink, egressActLink, departureTime,
                router, travelTime);
        double unsharedRideTime = unsharedPath.getTravelTime();//includes first & last link
        //TODO for this project, a single value is enough. To make it better, we can read from person's plan to determine the school starting time
        double schoolStartingTime = 28800;
        double maxTravelTime = schoolStartingTime - departureTime;
        double unsharedDistance = VrpPaths.calcDistance(unsharedPath);//includes last link

        DrtRoute route = routeFactories.createRoute(DrtRoute.class, accessActLink.getId(), egressActLink.getId());
        route.setDistance(unsharedDistance);
        route.setTravelTime(maxTravelTime);
        route.setDirectRideTime(unsharedRideTime);
        route.setMaxWaitTime(drtCfg.maxWaitTime);

        if (this.drtCfg.storeUnsharedPath) {
            route.setUnsharedPath(unsharedPath);
        }

        return route;
    }


    public static class SchoolTripsDrtRouteCreatorProvider extends ModalProviders.AbstractProvider<DvrpMode, SchoolTrafficRouteCreator> {
        private final LeastCostPathCalculatorFactory leastCostPathCalculatorFactory;

        private final DrtConfigGroup drtCfg;

        public SchoolTripsDrtRouteCreatorProvider(DrtConfigGroup drtCfg) {
            super(drtCfg.getMode(), DvrpModes::mode);
            this.drtCfg = drtCfg;
            leastCostPathCalculatorFactory = new SpeedyALTFactory();
        }

        @Override
        public SchoolTrafficRouteCreator get() {
            var travelTime = getModalInstance(TravelTime.class);
            return new SchoolTrafficRouteCreator(drtCfg, getModalInstance(Network.class), leastCostPathCalculatorFactory,
                    travelTime, getModalInstance(TravelDisutilityFactory.class));
        }
    }

}
