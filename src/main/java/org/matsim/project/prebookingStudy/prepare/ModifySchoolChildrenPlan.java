package org.matsim.project.prebookingStudy.prepare;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.common.util.DistanceUtils;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.geometry.CoordUtils;
import picocli.CommandLine;

import java.util.List;
import java.util.Random;

/**
 * This script is used to modify the departure time of school children's plan based on school starting time.
 * <p>
 * There are some unrealistic/inappropriate plans from the SNZ data. Some modification is required to make the
 * output of the runs more meaningful
 */
public class ModifySchoolChildrenPlan implements MATSimAppCommand {
    private static final Logger log = Logger.getLogger(ModifySchoolChildrenPlan.class);

    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--plans", description = "path to input plan file", required = true)
    private String plansPath;

    @CommandLine.Option(names = "--network", description = "path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--alpha", description = "travel time alpha", defaultValue = "2.0")
    private double alpha;

    @CommandLine.Option(names = "--beta", description = "travel time beta", defaultValue = "1200.0")
    private double beta;

    @CommandLine.Option(names = "--output", description = "path to output plans file", required = true)
    private String outputPath;

    private final Random random = new Random(1234);

    public static void main(String[] args) {
        new ModifySchoolChildrenPlan().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Config config = ConfigUtils.loadConfig(configPath);
        double walkingSpeed = config.plansCalcRoute().getTeleportedModeSpeeds().get(TransportMode.walk);
        log.info("Walking speed is " + walkingSpeed + " m/s");

        MultiModeDrtConfigGroup drtConfigGroup = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
        double stopDuration = drtConfigGroup.getModalElements().iterator().next().getStopDuration(); // Use the first Drt Config Group by default
        log.info("DRT Stop Duration is: " + stopDuration + " s");

        Network network = NetworkUtils.readNetwork(networkPath);
        Population plans = PopulationUtils.readPopulation(plansPath);
        UniformSchoolStartingTimeIdentification schoolStartTimeCalculator = new UniformSchoolStartingTimeIdentification();
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1.0);
        LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network, new OnlyTimeDependentTravelDisutility(travelTime), travelTime);

        for (Person person : plans.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            assert trips.size() == 1 : "There should only be 1 trip per children (i.e., going to school in the morning)";
            for (TripStructureUtils.Trip trip : trips) {
                Activity homeActivity = trip.getOriginActivity();
                Activity schoolActivity = trip.getDestinationActivity();

                homeActivity.setStartTime(0);
                Link homeLink = NetworkUtils.getNearestLink(network, homeActivity.getCoord());
                if (CoordUtils.calcEuclideanDistance(homeLink.getToNode().getCoord(), homeActivity.getCoord()) >= 200) {
                    homeActivity.setCoord(homeLink.getToNode().getCoord());
                }

                Node from = NetworkUtils.getNearestLink(network, homeActivity.getCoord()).getToNode();
                Node to = NetworkUtils.getNearestLink(network, schoolActivity.getCoord()).getToNode();
                double originalDepartureTime = homeActivity.getEndTime().orElseThrow(RuntimeException::new);
                double estDirectTravelTime = router.calcLeastCostPath(from, to, originalDepartureTime, null, null).travelTime;

                double schoolStartingTime = schoolStartTimeCalculator.getSchoolStartingTime(schoolActivity);
                schoolActivity.setStartTime(schoolStartingTime);

                double walkingTime = Math.floor(DistanceUtils.calculateDistance(homeActivity.getCoord(), homeLink.getToNode().getCoord()) / walkingSpeed) + 1;
                double travelTimeAllowance = alpha * estDirectTravelTime + beta + walkingTime + stopDuration + 1;
                double updatedDepartureTime = schoolStartingTime - travelTimeAllowance;
                homeActivity.setEndTime(updatedDepartureTime);
            }
        }

        PopulationWriter populationWriter = new PopulationWriter(plans);
        populationWriter.write(outputPath);

        return 0;
    }

    // Initial implementation: Uniform school starting time at 8:00.
    // An interface for this is possible
    static class UniformSchoolStartingTimeIdentification {
        private final static double schoolStartingTime = 28800;  //08:00

        public double getSchoolStartingTime(Activity schoolActivity) {
            return schoolStartingTime;
        }
    }

}
