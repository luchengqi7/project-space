package org.matsim.project.drtRequestPatternIdentification.run;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.drtRequestPatternIdentification.basicStructures.DrtDemand;
import org.matsim.project.utils.LinkToLinkTravelTimeMatrix;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Deprecated
public class ExampleCode {

    public static void main(String[] args) {
        // Create scenario based on config file
        String configPath = "/path/to/config/file";
        if (args.length != 0) {
            configPath = args[0];
        }

        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        Scenario scenario = ScenarioUtils.loadScenario(config);
        MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
        DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next();

        Population population = scenario.getPopulation();
        Network network = scenario.getNetwork();

        // Create router (based on free speed)
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        TravelDisutility travelDisutility = new OnlyTimeDependentTravelDisutility(travelTime);
        LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);


        // Getting drt setups
        double alpha = drtConfigGroup.maxTravelTimeAlpha;
        double beta = drtConfigGroup.maxTravelTimeBeta;
        double maxWaitTime = drtConfigGroup.maxWaitTime;
        double stopDuration = drtConfigGroup.stopDuration;
        System.out.println("DRT setups: alpha, beta, maxWaitTime, stopDuration " + alpha + ", " + beta + ", " + maxWaitTime + ", " + stopDuration);

        // Go through input plans and collect all relevant links
        MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
        Set<Id<Link>> relevantLinks = new HashSet<>();
        List<DrtDemand> drtTrips = new ArrayList<>();

        for (Person person : population.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                if (mainModeIdentifier.identifyMainMode(trip.getTripElements()).equals(TransportMode.drt)) {
                    // here we have the drt trip
                    double departureTime = trip.getOriginActivity().getEndTime().orElseThrow(RuntimeException::new);

                    // When link id is written in th plan
                    Link fromLink = network.getLinks().get(trip.getOriginActivity().getLinkId());
                    Link toLink = network.getLinks().get(trip.getDestinationActivity().getLinkId());

                    // When link id is not provided (coordinate is provided instead)
//                    Link fromLink = NetworkUtils.getNearestLink(network, trip.getOriginActivity().getCoord());
//                    Link toLink = NetworkUtils.getNearestLink(network, trip.getDestinationActivity().getCoord());

                    drtTrips.add(new DrtDemand(person.getId().toString(), fromLink, toLink, departureTime));
                    relevantLinks.add(fromLink.getId());
                    relevantLinks.add(toLink.getId());
                }
            }
        }

        LinkToLinkTravelTimeMatrix travelTimeMatrix = new LinkToLinkTravelTimeMatrix(network, travelTime, relevantLinks, 0);

        // Calculate route
        for (DrtDemand trip : drtTrips) {
            double legTravelTime = travelTimeMatrix.getTravelTime(trip.fromLink(), trip.toLink(), trip.departureTime());
            System.out.println("Trip " + trip.tripIdString() + " has a travel time of " + legTravelTime + " seconds.");
        }

    }


}
