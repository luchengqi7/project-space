package org.matsim.project.drtSchoolTransportStudy.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.io.MatsimXmlWriter;
import org.matsim.core.utils.io.UncheckedIOException;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@CommandLine.Command(
        name = "prepare-drt-stops",
        description = "Prepare DRT stop files for stop-based service"
)
public class PrepareDrtStops implements MATSimAppCommand {
    @CommandLine.Option(names = "--network", description = "Path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--plans", description = "Path to plans file", required = true)
    private String inputPlansFile;

    @CommandLine.Option(names = "--output", description = "Path to output folder", required = true)
    private String outputFolder;

    @CommandLine.Option(names = "--max-distance", description = "Path to output folder", defaultValue = "500")
    private double maxDistance;


    public static void main(String[] args) {
        new PrepareDrtStops().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Network network = NetworkUtils.readNetwork(networkPath);
        Population plans = PopulationUtils.readPopulation(inputPlansFile);
        Set<Link> stopLinksToWrite = new HashSet<>();

        List<Id<Person>> personsNotYetCovered = new ArrayList<>();
        Map<Id<Person>, Coord> personToHomeCoordMap = new HashMap<>();
        Map<Id<Person>, Set<Id<Link>>> personIdToLinkIdsMap = new HashMap<>();
        Map<Id<Link>, Set<Id<Person>>> linkIdToPersonIdsMap = new HashMap<>();

        Map<Id<Link>, Double> linkSignificanceMap = new HashMap<>();
        for (Id<Link> linkId : network.getLinks().keySet()) {
            linkSignificanceMap.put(linkId, 0.0);
        }

        for (Person person : plans.getPersons().values()) {
            TripStructureUtils.Trip trip = TripStructureUtils.getTrips(person.getSelectedPlan()).get(0); // In this project, there is 1 school trip per student
            Link toLink = NetworkUtils.getNearestLink(network, trip.getDestinationActivity().getCoord());
            stopLinksToWrite.add(toLink); // add DRT stop for school (if not yet in the set)

            Coord fromCoord = trip.getOriginActivity().getCoord();
            personToHomeCoordMap.put(person.getId(), fromCoord);
            personsNotYetCovered.add(person.getId());

            for (Link link : network.getLinks().values()) {
                double distance = CoordUtils.calcEuclideanDistance(fromCoord, link.getToNode().getCoord());
                if (distance <= maxDistance) {
                    double normalizedDistance = distance / maxDistance;
                    personIdToLinkIdsMap.computeIfAbsent(person.getId(), s -> new HashSet<>()).add(link.getId());
                    linkIdToPersonIdsMap.computeIfAbsent(link.getId(), s -> new HashSet<>()).add(person.getId());
                    double updatedSignificance = linkSignificanceMap.get(link.getId()) + 2 - Math.pow(normalizedDistance, 2);
                    linkSignificanceMap.put(link.getId(), updatedSignificance);
                }
            }
        }

        while (!personsNotYetCovered.isEmpty()) {
            // Choose the link with the highest score (significance) and set a DRT stop there
            Id<Link> linkId = linkSignificanceMap.entrySet().stream().
                    sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).iterator().next().getKey();
            stopLinksToWrite.add(network.getLinks().get(linkId));

            // Remove the persons from the uncovered persons list and remove the contribution to the links score by those persons
            Set<Id<Person>> personCovered = linkIdToPersonIdsMap.get(linkId);
            personCovered.retainAll(personsNotYetCovered);
            for (Id<Person> personId : personCovered) {
                for (Id<Link> nearbyLinkId : personIdToLinkIdsMap.get(personId)) {
                    double normalizedDistance = CoordUtils.calcEuclideanDistance(network.getLinks().get(nearbyLinkId).getToNode().getCoord(), personToHomeCoordMap.get(personId)) / maxDistance;
                    double updatedSignificance = linkSignificanceMap.get(nearbyLinkId) - (2 - Math.pow(normalizedDistance, 2));
                    linkSignificanceMap.put(nearbyLinkId, updatedSignificance);
                }
            }
            personsNotYetCovered.removeAll(personCovered);
        }

        // write down drt stops
        DrtStopWriter drtStopWriter = new DrtStopWriter(outputFolder);
        drtStopWriter.write(stopLinksToWrite);

        return 0;
    }

    static class DrtStopWriter extends MatsimXmlWriter {
        private final String mode = TransportMode.drt;
        private final String outputFolder;

        DrtStopWriter(String outputFolder) {
            this.outputFolder = outputFolder;
        }


        public void write(Set<Link> stopLinks) throws UncheckedIOException, IOException {
            this.openFile(outputFolder + "/" + mode + "-stops.xml");
            this.writeXmlHead();
            this.writeDoctype("transitSchedule", "http://www.matsim.org/files/dtd/transitSchedule_v1.dtd");
            this.writeStartTag("transitSchedule", null);
            this.writeStartTag("transitStops", null);
            this.writeTransitStops(stopLinks);
            this.writeEndTag("transitStops");
            this.writeEndTag("transitSchedule");
            this.close();
        }

        private void writeTransitStops(Set<Link> stopLinks) throws IOException {
            // Write csv file for adjusted stop location
            FileWriter csvWriter = new FileWriter(outputFolder + "/"
                    + mode + "-stops-locations.csv");
            csvWriter.append("Stop ID");
            csvWriter.append(",");
            csvWriter.append("Link ID");
            csvWriter.append(",");
            csvWriter.append("X");
            csvWriter.append(",");
            csvWriter.append("Y");
            csvWriter.append("\n");

            int counter = 0;
            for (Link stopLink : stopLinks) {
                List<Tuple<String, String>> attributes = new ArrayList<Tuple<String, String>>(5);
                attributes.add(createTuple("id", Integer.toString(counter)));
                attributes.add(createTuple("x", Double.toString(stopLink.getToNode().getCoord().getX())));
                attributes.add(createTuple("y", Double.toString(stopLink.getToNode().getCoord().getY())));
                attributes.add(createTuple("linkRefId", stopLink.getId().toString()));
                this.writeStartTag("stopFacility", attributes, true);

                csvWriter.append(Integer.toString(counter));
                csvWriter.append(",");
                csvWriter.append(stopLink.getId().toString());
                csvWriter.append(",");
                csvWriter.append(Double.toString(stopLink.getToNode().getCoord().getX()));
                csvWriter.append(",");
                csvWriter.append(Double.toString(stopLink.getToNode().getCoord().getY()));
                csvWriter.append("\n");

                counter++;
            }
            csvWriter.close();
        }
    }
}
