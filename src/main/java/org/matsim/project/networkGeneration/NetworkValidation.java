package org.matsim.project.networkGeneration;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.analysis.vsp.traveltimedistance.GoogleMapRouteValidator;
import org.matsim.contrib.analysis.vsp.traveltimedistance.HereMapsRouteValidator;
import org.matsim.contrib.analysis.vsp.traveltimedistance.TravelTimeDistanceValidator;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;
import picocli.CommandLine;

import java.io.FileWriter;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

public class NetworkValidation implements MATSimAppCommand {
    @CommandLine.Option(names = "--api", description = "path to network file", defaultValue = "GOOGLE_MAP")
    private API api;

    @CommandLine.Option(names = "--api-key", description = "API key", required = true)
    private String apiKey;

    @CommandLine.Option(names = "--network", description = "path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--plans", description = "plans to be validated", required = true)
    private String plansPath;

    @CommandLine.Option(names = "--output", description = "output folder for the route calculation", required = true)
    private String outputPath;

    @CommandLine.Option(names = "--date", description = "The date to validate travel times for, format: YYYY-MM-DD")
    private LocalDate date;

    @CommandLine.Option(names = "--max-validation", description = "output folder for the route calculation", defaultValue = "2000")
    private double maxValidations;

    private enum API {
        HERE, GOOGLE_MAP, NETWORK_FILE
    }

    private final String mode = "car";

    public static void main(String[] args) {
        new NetworkValidation().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        CoordinateTransformation ct = new GeotoolsTransformation("EPSG:25832", "EPSG:4326");
        TravelTimeDistanceValidator validator;
        MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();

        if (api == API.GOOGLE_MAP) {
            if (date == null) {
                date = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
            }
            validator = new GoogleMapRouteValidator(outputPath, mode, apiKey, date.toString(), ct);

        } else if (api == API.HERE) {
            if (date == null) {
                date = LocalDate.now().with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY));
            }
            validator = new HereMapsRouteValidator(outputPath, mode, apiKey, date.toString(), ct, false);

        } else {
            throw new RuntimeException("Wrong API used. Allowed values for --api are: GOOGLE_MAP, HERE. Do not use NETWORK_BASED validator in this analysis");
        }

        Population population = PopulationUtils.readPopulation(plansPath);
        Network network = NetworkUtils.readNetwork(networkPath);
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1.0);
        LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network, new OnlyTimeDependentTravelDisutility(travelTime), travelTime);

        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(outputPath + "/network-validation-" + api.toString() + ".tsv"), CSVFormat.TDF);
        tsvWriter.printRecord("trip_id", "from_x", "from_y", "to_x", "to_y", "network_travel_time", "validated_travel_time", "network_travel_distance", "validated_travel_distance");

        int validated = 0;
        for (Person person : population.getPersons().values()) {
            int personTripCounter = 0;
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                String mainMode = mainModeIdentifier.identifyMainMode(trip.getTripElements());
                if (!mainMode.equals(TransportMode.car) && !mainMode.equals(TransportMode.drt)) {
                    continue;
                }
                String tripId = person.getId().toString() + "_" + personTripCounter;
                Coord from = trip.getOriginActivity().getCoord();
                if (from == null) {
                    from = network.getLinks().get(trip.getOriginActivity().getLinkId()).getToNode().getCoord();
                }
                Link fromLink = NetworkUtils.getNearestLink(network, from);

                Coord to = trip.getDestinationActivity().getCoord();
                if (to == null) {
                    to = network.getLinks().get(trip.getDestinationActivity().getLinkId()).getToNode().getCoord();
                }
                Link toLink = NetworkUtils.getNearestLink(network, to);

                double departureTime = trip.getOriginActivity().getEndTime().orElseThrow(RuntimeException::new);
//                double departureTime = 3600;   // To test free speed travel time

                Tuple<Double, Double> validatedResult = validator.getTravelTime(from, to, departureTime, tripId);
                LeastCostPathCalculator.Path route = router.calcLeastCostPath(fromLink.getToNode(), toLink.getToNode(), departureTime, null, null);
                double networkTravelDistance = route.links.stream().mapToDouble(Link::getLength).sum();

                tsvWriter.printRecord(tripId, from.getX(), from.getY(), to.getX(), to.getY(), route.travelTime, validatedResult.getFirst(), networkTravelDistance, validatedResult.getSecond());

                validated++;
                personTripCounter++;
                Thread.sleep(100);
            }

            if (validated >= maxValidations) {
                break;
            }
        }
        tsvWriter.close();
        return 0;
    }
}
