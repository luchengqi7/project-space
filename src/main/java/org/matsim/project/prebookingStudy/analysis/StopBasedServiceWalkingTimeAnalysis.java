package org.matsim.project.prebookingStudy.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.common.util.DistanceUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class StopBasedServiceWalkingTimeAnalysis {
    public static void main(String[] args) throws IOException {
        String drtStops = "/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/scenarios/input/drt-stops-locations.csv";
        Network network = NetworkUtils.readNetwork("/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/scenarios/input/vulkaneifel-v1.0-network.xml.gz");
        Population plans = PopulationUtils.readPopulation("/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/scenarios/input/case-study-plans/alpha_2-beta_1200.plans.xml.gz");
        List<Link> drtStopLinks = new ArrayList<>();

        try (CSVParser parser = new CSVParser(Files.newBufferedReader(Path.of(drtStops)),
                CSVFormat.DEFAULT.withDelimiter(',').withFirstRecordAsHeader())) {
            for (CSVRecord record : parser.getRecords()) {
                Id<Link> linkId = Id.createLinkId(record.get(1));
                drtStopLinks.add(network.getLinks().get(linkId));
            }
        }


        double totalWalkingDistance = 0;
        double totalPerson = 0;
        for (Person person : plans.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            assert trips.size() == 1;
            TripStructureUtils.Trip trip = trips.get(0);
            Coord homeCoord = trip.getOriginActivity().getCoord();
            Coord schoolCoord = trip.getDestinationActivity().getCoord();
            Link boardingStop = findClosestDrtStopLink(homeCoord, drtStopLinks);
            Link alightingStop = findClosestDrtStopLink(schoolCoord, drtStopLinks);
            if (!alightingStop.getId().toString().equals(boardingStop.getId().toString())) {
                double walkDistance = CoordUtils.calcEuclideanDistance(homeCoord, boardingStop.getToNode().getCoord());
                totalWalkingDistance += walkDistance;  //We only consider the walking distance from home to boarding stop
                totalPerson += 1;
            }
        }

        double averageDistance = totalWalkingDistance / totalPerson;
        double averageWalkingTime = averageDistance / 0.833333;
        System.out.println("Average walking distance = " + averageDistance);
        System.out.println("Average walking time = " + averageWalkingTime);

    }

    private static Link findClosestDrtStopLink(Coord homeCoord, List<Link> drtStopLinks) {
        double minDistance = Double.MAX_VALUE;
        Link closestDrtStopLink = null;
        for (Link link : drtStopLinks) {
            Coord stopCoord = link.getToNode().getCoord();
            double distance = DistanceUtils.calculateDistance(homeCoord, stopCoord);
            if (distance < minDistance) {
                minDistance = distance;
                closestDrtStopLink = link;
            }
        }
        return closestDrtStopLink;
    }
}
