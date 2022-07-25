package org.matsim.project.drtSchoolTransportStudy.analysis.preAnalysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import picocli.CommandLine;

import java.io.FileWriter;
import java.util.List;

@CommandLine.Command(
        name = "school-traffic-analysis",
        description = "analyze the school traffic plans (pre-analysis)"
)
public class SchoolTrafficAnalysis implements MATSimAppCommand {
    @CommandLine.Option(names = "--plans", description = "path to plan file", required = true)
    private String plansPath;

    @CommandLine.Option(names = "--network", description = "path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--output", description = "path to network file", required = true)
    private String outputPath;

    private final static double schoolStartingTime = 28800; // 08:00

    public static void main(String[] args) {
        new SchoolTrafficAnalysis().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Population population = PopulationUtils.readPopulation(plansPath);
        Network network = NetworkUtils.readNetwork(networkPath);

        TravelTime travelTime = new QSimFreeSpeedTravelTime(1.0);
        LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network, new OnlyTimeDependentTravelDisutility(travelTime), travelTime);

        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(outputPath), CSVFormat.TDF);
        tsvWriter.printRecord("trip_id", "planned_departure_time", "planned_arrival_time", "planned_travel_time", "est_car_direct_travel_time", "est_earliest_arrival_time", "travel_time_allowance");

        for (Person person : population.getPersons().values()) {
            int tripCounter = 0;
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                double plannedDepartureTime = trip.getOriginActivity().getEndTime().orElseThrow(RuntimeException::new);
                double plannedArrivalTime = trip.getDestinationActivity().getStartTime().orElse(86400);
                if (plannedArrivalTime == 86400) {
                    continue;
                }
                double plannedTravelTime = plannedArrivalTime - plannedDepartureTime;
                Link fromLink = NetworkUtils.getNearestLink(network, trip.getOriginActivity().getCoord());
                Link toLink = NetworkUtils.getNearestLink(network, trip.getDestinationActivity().getCoord());
                double estimatedDirectTravelTime = VrpPaths.calcAndCreatePath(fromLink, toLink, plannedDepartureTime, router, travelTime).getTravelTime();
                double estimatedEarliestArrivalTime = plannedDepartureTime + estimatedDirectTravelTime;
                double travelTimeAllowance = schoolStartingTime - plannedDepartureTime;
                tsvWriter.printRecord(person.getId().toString() + "_" + tripCounter,
                        plannedDepartureTime, plannedArrivalTime, plannedTravelTime,
                        estimatedDirectTravelTime, estimatedEarliestArrivalTime,
                        travelTimeAllowance);
                tripCounter++;
            }
        }

        tsvWriter.close();
        return 0;
    }
}
