package org.matsim.project.drtRequestPatternIdentification;

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
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.ArrayList;
import java.util.List;

public class RunDemandQuantification {
    public static void main(String[] args) {
        String configPath = "/path/to/config/file";
        if (args.length != 0) {
            configPath = args[0];
        }

        // Load scenario from config file
        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        Scenario scenario = ScenarioUtils.loadScenario(config);
        MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
        DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next();
        Population population = scenario.getPopulation();
        Network network = scenario.getNetwork();

        // Collect DRT trips
        MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
        List<DrtDemand> demands = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                if (mainModeIdentifier.identifyMainMode(trip.getTripElements()).equals(TransportMode.drt)) {
                    double departureTime = trip.getOriginActivity().getEndTime().orElseThrow(RuntimeException::new);
                    Link fromLink = network.getLinks().get(trip.getOriginActivity().getLinkId());
                    Link toLink = network.getLinks().get(trip.getDestinationActivity().getLinkId());
                    demands.add(new DrtDemand(person.getId().toString(), fromLink, toLink, departureTime));
                }
            }
        }

        // Perform demand quantification
        DefaultDrtDemandsQuantificationTool quantificationTool = new DefaultDrtDemandsQuantificationTool(drtConfigGroup, network);
        double score = quantificationTool.performQuantification(demands);


    }
}
