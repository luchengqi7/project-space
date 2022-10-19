package org.matsim.project.drtOperationStudy.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.RandomizingTimeDistanceTravelDisutilityFactory;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.project.drtSchoolTransportStudy.analysis.TrafficAnalysis;
import picocli.CommandLine;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.matsim.application.ApplicationUtils.globFile;

public class DrtServiceQualityAnalysis implements MATSimAppCommand {
    @CommandLine.Option(names = "--directory", description = "path to matsim output directory", required = true)
    private Path directory;

    private final Logger log = LogManager.getLogger(DrtServiceQualityAnalysis.class);

    public static void main(String[] args) {
        new DrtServiceQualityAnalysis().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        log.info("Begin analyzing service quality...");
        String mode = "drt";
        Path configPath = globFile(directory, "*output_config.*");
        Path networkPath = globFile(directory, "*output_network.*");
        Path eventPath = globFile(directory, "*output_events.*");
        Path outputFolder = Path.of(directory.toString() + "/analysis-drt-service-quality");
        if (!Files.exists(outputFolder)) {
            Files.createDirectory(outputFolder);
        }

        Config config = ConfigUtils.loadConfig(configPath.toString());
        int lastIteration = config.controler().getLastIteration();
        Path folderOfLastIteration = Path.of(directory.toString() + "/ITERS/it." + lastIteration);
        MultiModeDrtConfigGroup multiModeDrtConfigGroup = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);

        Network network = NetworkUtils.readNetwork(networkPath.toString());
        TravelTime travelTime = TrafficAnalysis.analyzeTravelTimeFromEvents(network, eventPath.toString());

        config.plansCalcRoute().setRoutingRandomness(0);
        TravelDisutility travelDisutility = new RandomizingTimeDistanceTravelDisutilityFactory
                (TransportMode.car, config).createTravelDisutility(travelTime);
        LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);

        Path tripsFile = globFile(folderOfLastIteration, "*drt_legs_" + mode + ".*");
        Path outputStatsPath = Path.of(outputFolder + "/" + mode + "_KPI.tsv");

        CSVPrinter tsvWriterKPI = new CSVPrinter(new FileWriter(outputStatsPath.toString()), CSVFormat.TDF);
        List<String> titleRowKPI = Arrays.asList
                ("number_of_requests", "mean_riding_time", "mean_direct_drive_time", "mean_actual_distance", "mean_est_direct_distance",
                        "onboard_delay_ratio_mean", "detour_distance_ratio_mean");
        tsvWriterKPI.printRecord(titleRowKPI);

        int numOfTrips = 0;
        double sumDirectDriveTime = 0;
        double sumActualRidingTime = 0;
        double sumDirectDriveDistance = 0;
        double sumActualTravelDistance = 0;

        try (CSVParser parser = new CSVParser(Files.newBufferedReader(tripsFile),
                CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader())) {
            for (CSVRecord record : parser.getRecords()) {
                numOfTrips++;
                Link fromLink = network.getLinks().get(Id.createLinkId(record.get(3)));
                Link toLink = network.getLinks().get(Id.createLinkId(record.get(6)));
                double departureTime = Double.parseDouble(record.get(0));

                LeastCostPathCalculator.Path path = router.calcLeastCostPath(fromLink.getToNode(), toLink.getFromNode(),
                        departureTime, null, null);
                path.links.add(toLink);
                double estDirectDriveTime = path.travelTime + travelTime.getLinkTravelTime(toLink, path.travelTime + departureTime, null, null) + 2;
                sumDirectDriveTime += estDirectDriveTime;

                double actualInVehicleTime = Double.parseDouble(record.get(11));
                sumActualRidingTime += actualInVehicleTime;

                double estDirectDriveDistance = path.links.stream().map(Link::getLength).mapToDouble(l -> l).sum();
                sumDirectDriveDistance += estDirectDriveDistance;

                double actualTravelDistance = Double.parseDouble(record.get(12));
                sumActualTravelDistance += actualTravelDistance;
            }

            List<String> outputRow = new ArrayList<>();
            outputRow.add(Integer.toString(numOfTrips));
            outputRow.add(Double.toString(sumActualRidingTime / numOfTrips));
            outputRow.add(Double.toString(sumDirectDriveTime / numOfTrips));
            outputRow.add(Double.toString(sumActualTravelDistance / numOfTrips));
            outputRow.add(Double.toString(sumDirectDriveDistance / numOfTrips));
            outputRow.add(Double.toString(sumActualRidingTime / sumDirectDriveTime - 1));
            outputRow.add(Double.toString(sumActualTravelDistance / sumDirectDriveDistance - 1));
            tsvWriterKPI.printRecord(outputRow);
        }

        tsvWriterKPI.close();

        return 0;
    }
}
