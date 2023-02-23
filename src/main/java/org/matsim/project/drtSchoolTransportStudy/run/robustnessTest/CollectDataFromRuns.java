package org.matsim.project.drtSchoolTransportStudy.run.robustnessTest;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.matsim.application.MATSimAppCommand;
import picocli.CommandLine;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CollectDataFromRuns implements MATSimAppCommand {
    @CommandLine.Option(names = "--directory", description = "root output folder", required = true)
    private String output;

    @CommandLine.Option(names = "--common-part-a", description = "output folder front", defaultValue = "experiment-seed-")
    private String commonPartA;

    @CommandLine.Option(names = "--common-part-b", description = "output folder back", defaultValue = "-alpha_2-beta_1200-UNIFORM-DOOR_TO_DOOR")
    private String commonPartB;

    @CommandLine.Option(names = "--seeds", description = "seeds, separated with ,", defaultValue = "1,2,3,4,5")
    private String seedsString;

    public static void main(String[] args) {
        new CollectDataFromRuns().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        String[] seedsSplit = seedsString.split(",");

        List<Double> latestArrivalTimes = new ArrayList<>();

        for (String seed : seedsSplit) {
            for (int i = 0; i < 101; i++) {
                String tripsFile = output + "/" + commonPartA + seed + commonPartB + "/fleet-size-85/ITERS/it." + i + "/" + i + ".drt_legs_drt.csv";
                double latestArrivalTime = 0;
                try (CSVParser parser = new CSVParser(Files.newBufferedReader(Path.of(tripsFile)),
                        CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader())) {
                    for (CSVRecord record : parser.getRecords()) {
                        double arrivalTime = Double.parseDouble(record.get("arrivalTime"));
                        if (arrivalTime > latestArrivalTime) {
                            latestArrivalTime = arrivalTime;
                        }
                    }
                }
                latestArrivalTimes.add(latestArrivalTime);
            }
        }

        CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(output + "/latest-arrival-times.tsv"), CSVFormat.TDF);
        csvPrinter.printRecord("latest_arrival_time");
        for (double latestArrivalTime : latestArrivalTimes) {
            csvPrinter.printRecord(Double.toString(latestArrivalTime));
        }
        csvPrinter.close();

        return 0;
    }
}
