package org.matsim.project.drtRequestPatternIdentification.prepare;

import com.google.common.base.Preconditions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PrepareDrtRequestsRandomSelection implements MATSimAppCommand {
    @CommandLine.Option(names = "--input", description = "path to input plans", required = true)
    private String inputPopulation;

    @CommandLine.Option(names = "--network", description = "path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--output", description = "output drt plans", required = true)
    private String outputPopulation;

    @CommandLine.Option(names = "--trips", description = "Number of trips to keep", defaultValue = "1000000")
    private int numberOfTrips;

    @CommandLine.Option(names = "--percent", description = "Percentage of total trips", defaultValue = "1.0")
    private double percentage;

    @CommandLine.Option(names = "--start-time", description = "Service hour start time", defaultValue = "0")
    private double startTime;

    @CommandLine.Option(names = "--end-time", description = "Service hour end time", defaultValue = "86400")
    private double endTime;

    @CommandLine.Option(names = "--min-euclidean-distance", description = "filter out short trips", defaultValue = "500")
    private double minTripEuclideanDistance;

    @CommandLine.Mixin
    private ShpOptions shp = new ShpOptions();

    private final Logger log = LogManager.getLogger(PrepareDrtRequestsRandomSelection.class);

    public static void main(String[] args) {
        new PrepareDrtRequestsRandomSelection().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Preconditions.checkArgument(percentage <= 1.0, "The percentage should not be greater than 1!");

        Population inputPlans = PopulationUtils.readPopulation(inputPopulation);
        Population outputPlans = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory populationFactory = inputPlans.getFactory();
        Network network = NetworkUtils.readNetwork(networkPath);

        List<Link> linksToRemove = new ArrayList<>();
        for (Link link : network.getLinks().values()) {
            if (!link.getAllowedModes().contains(TransportMode.car)) {
                linksToRemove.add(link);
            }
        }
        for (Link link : linksToRemove) {
            network.removeLink(link.getId());
        }

        Geometry serviceArea = shp.isDefined() ? shp.getGeometry() : null;

        int totalPersons = inputPlans.getPersons().size();
        log.info("There are total " + totalPersons + " persons to be processed");
        int counter = 0;
        int percent = 0;
        int percent10 = (int) (0.1 * totalPersons);
        List<TripStructureUtils.Trip> allTrips = new ArrayList<>();
        for (Person person : inputPlans.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                double departureTime = trip.getOriginActivity().getEndTime().orElse(-1);
                if (departureTime < startTime || departureTime > endTime) {
                    continue;
                }

                if (serviceArea != null) {
                    Point fromPoint = MGC.coord2Point(trip.getOriginActivity().getCoord());
                    Point toPoint = MGC.coord2Point(trip.getDestinationActivity().getCoord());
                    if (!fromPoint.within(serviceArea) || !toPoint.within(serviceArea)) {
                        continue;
                    }
                }

                double euclideanDistance =
                        CoordUtils.calcEuclideanDistance(trip.getOriginActivity().getCoord(), trip.getDestinationActivity().getCoord());
                if (euclideanDistance <= minTripEuclideanDistance) {
                    continue;
                }

                allTrips.add(trip);
            }

            counter++;
            if (counter % percent10 == 0) {
                percent += 10;
                log.info("Processing: " + percent + "% processed");
            }
        }

        log.info("There are in total " + allTrips.size() + " potential DRT trips in total.");
        int numberOfTripsToKeep = (int) Math.min(numberOfTrips, percentage * allTrips.size());
        Collections.shuffle(allTrips, new Random(4711));
        for (int i = 0; i < numberOfTripsToKeep; i++) {
            TripStructureUtils.Trip trip = allTrips.get(i);
            Coord fromCoord = trip.getOriginActivity().getCoord();
            Coord toCoord = trip.getDestinationActivity().getCoord();
            Link fromLink = NetworkUtils.getNearestLink(network, fromCoord);
            Link toLink = NetworkUtils.getNearestLink(network, toCoord);

            Activity fromAct = populationFactory.createActivityFromLinkId("dummy", fromLink.getId());
            fromAct.setEndTime(trip.getOriginActivity().getEndTime().orElseThrow(RuntimeException::new));
            Leg leg = populationFactory.createLeg(TransportMode.drt);
            Activity toAct = populationFactory.createActivityFromLinkId("dummy", toLink.getId());

            Person person = populationFactory.createPerson(Id.createPersonId("drt_" + i));
            Plan plan = populationFactory.createPlan();
            plan.addActivity(fromAct);
            plan.addLeg(leg);
            plan.addActivity(toAct);
            person.addPlan(plan);
            outputPlans.addPerson(person);
        }

        PopulationWriter populationWriter = new PopulationWriter(outputPlans);
        populationWriter.write(outputPopulation);
        return 0;
    }

}
