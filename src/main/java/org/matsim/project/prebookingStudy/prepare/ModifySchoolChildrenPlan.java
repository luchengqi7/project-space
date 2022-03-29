package org.matsim.project.prebookingStudy.prepare;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
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
    @CommandLine.Option(names = "--plans", description = "path to plan file", required = true)
    private String plansPath;

    @CommandLine.Option(names = "--network", description = "path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--output", description = "path to output plans file", required = true)
    private String outputPath;

    private final double boardingTime = 60;
    private final double alphaLower = 1.0;
    private final double alphaUpper = 2.0;
    private final double betaLower = 200; // Account for the time to walk to the link
    private final double betaUpper = 1800;

    private final Random random = new Random(1234);

    public static void main(String[] args) {
        new ModifySchoolChildrenPlan().execute(args);
    }

    @Override
    public Integer call() throws Exception {
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
                double earliestDepartureTime = schoolStartingTime - alphaUpper * estDirectTravelTime - betaUpper - boardingTime;
                double latestDepartureTime = schoolStartingTime - alphaLower * estDirectTravelTime - betaLower - boardingTime;
                assert earliestDepartureTime >= 0 && latestDepartureTime >= 0;

                if (originalDepartureTime >= latestDepartureTime || originalDepartureTime <= earliestDepartureTime) {
                    double newDepartureTime = earliestDepartureTime + random.nextInt((int) (latestDepartureTime - earliestDepartureTime));
                    homeActivity.setEndTime(newDepartureTime);
                }
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
