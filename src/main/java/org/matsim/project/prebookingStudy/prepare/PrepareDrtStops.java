package org.matsim.project.prebookingStudy.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.common.util.DistanceUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.collections.Tuple;
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

        // A very simple method to create stops (based home location): if there is no stop nearby, then add a stop to the home link
        List<Person> personList = new ArrayList<>(plans.getPersons().values());

        int numOfStops = Integer.MAX_VALUE;
        for (int i = 0; i < 100; i++) {
            Collections.shuffle(personList);
            Set<Link> stopLinks = new HashSet<>();

            for (Person person : personList) {
                for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                    Activity fromAct = trip.getOriginActivity();
                    Coord fromCoord = fromAct.getCoord();
                    Link fromLink = network.getLinks().get(fromAct.getLinkId());

                    boolean coveredByAStop = false;
                    for (Link stopLink : stopLinks) {
                        if (DistanceUtils.calculateDistance(stopLink.getToNode().getCoord(), fromCoord) < maxDistance) {
                            coveredByAStop = true;
                            break;
                        }
                    }

                    if (!coveredByAStop) {
                        stopLinks.add(fromLink);
                    }

                    Link toLink = NetworkUtils.getNearestLink(network, trip.getDestinationActivity().getCoord());
                    stopLinks.add(toLink);  // Add school link directly (when it is not in the set already)
                }
            }

            if (stopLinks.size() < numOfStops) {
                numOfStops = stopLinks.size();
                stopLinksToWrite = stopLinks;
                System.out.println("Number of stops = " + numOfStops);
            }
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
