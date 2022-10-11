package org.matsim.project.drtOperationStudy.analysis;

import com.google.common.base.Preconditions;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import picocli.CommandLine;

import java.io.FileWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class DrtRequestsPreAnalysis implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "input DRT plans", required = true)
    private String configPathString;

//    @CommandLine.Option(names = "--plans", description = "input DRT plans", required = true)
//    private String inputDrtPlansPathString;
//
//    @CommandLine.Option(names = "--network", description = "input network", required = true)
//    private String networkPathString;

    @CommandLine.Option(names = "--output", description = "path to matsim output directory", required = true)
    private String outputPath;

    public static void main(String[] args) {
        new DrtRequestsPreAnalysis().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Config config = ConfigUtils.loadConfig(configPathString);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Population plans = scenario.getPopulation();
        Network network = scenario.getNetwork();
        MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();

        SpeedyALTFactory routerFactory = new SpeedyALTFactory();
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        LeastCostPathCalculator router = routerFactory
                .createPathCalculator(network, new OnlyTimeDependentTravelDisutility(travelTime), travelTime);

        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;

        int numOfRequests = 0;
        double sumEuclideanDistance = 0;
        double sumNetworkDistance = 0;
        double sumDirectTravelTime = 0;

        for (Person person : plans.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                Preconditions.checkArgument(mainModeIdentifier.identifyMainMode(trip.getTripElements()).equals(TransportMode.drt));
                Activity originAct = trip.getOriginActivity();
                Activity destinationAct = trip.getDestinationActivity();

                Link fromLink = network.getLinks().get(originAct.getLinkId());
                Link toLink = network.getLinks().get(destinationAct.getLinkId());

                Coord fromCoord = originAct.getCoord();
                Coord toCoord = destinationAct.getCoord();

                if (fromCoord == null) {
                    fromCoord = fromLink.getToNode().getCoord();
                }

                if (toCoord == null) {
                    toCoord = toLink.getToNode().getCoord();
                }

                // Determine the boundary rectangle
                if (fromCoord.getX() > maxX) {
                    maxX = fromCoord.getX();
                }

                if (fromCoord.getY() > maxY) {
                    maxY = fromCoord.getY();
                }

                if (fromCoord.getX() < minX) {
                    minX = fromCoord.getX();
                }

                if (fromCoord.getY() < minY) {
                    minY = fromCoord.getY();
                }

                double euclideanDistance = CoordUtils.calcEuclideanDistance(fromCoord, toCoord);
                LeastCostPathCalculator.Path path =
                        router.calcLeastCostPath(fromLink.getToNode(), toLink.getFromNode(),
                                originAct.getEndTime().orElse(0), null, null);
                path.links.add(toLink);
                double networkDistance = path.links.stream().mapToDouble(Link::getLength).sum();
                double directTravelTime = path.travelTime + 2;

                sumEuclideanDistance += euclideanDistance;
                sumNetworkDistance += networkDistance;
                sumDirectTravelTime += directTravelTime;

                numOfRequests++;
            }
        }

        double area = (maxX - minX) / 1000 * (maxY - minY) / 1000;
        double density = numOfRequests / area;

        DecimalFormat df = new DecimalFormat("0.0");
        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(outputPath + "/drt-requests-characteristics.tsv"), CSVFormat.TDF);
        tsvWriter.printRecord("number_of_requests", "rectangle_area", "request_density", "mean_euclidean_dist", "mean_network_dist", "mean_travel_time");

        List<String> outputRow = new ArrayList<>();
        outputRow.add(Integer.toString(numOfRequests));
        outputRow.add(df.format(area));
        outputRow.add(df.format(density));
        outputRow.add(df.format(sumEuclideanDistance / numOfRequests));
        outputRow.add(df.format(sumNetworkDistance / numOfRequests));
        outputRow.add(df.format(sumDirectTravelTime / numOfRequests));
        tsvWriter.printRecord(outputRow);

        tsvWriter.close();

        return 0;
    }
}
