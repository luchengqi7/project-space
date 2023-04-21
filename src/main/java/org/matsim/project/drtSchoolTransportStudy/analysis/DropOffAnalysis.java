package org.matsim.project.drtSchoolTransportStudy.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.application.MATSimAppCommand;
import picocli.CommandLine;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Analyze the distribution of number of passengers dropped off at each stop (i.e., school)
 */
public class DropOffAnalysis implements MATSimAppCommand {
    private final Logger log = LogManager.getLogger(DropOffAnalysis.class);

    @CommandLine.Option(names = "--directory", description = "drt legs file", required = true)
    private Path directory;

    @CommandLine.Option(names = "--drt-legs", description = "drt legs file", required = true)
    private Path drtLegsFile;

    @CommandLine.Option(names = "--stop-duration", description = "drt legs file", defaultValue = "10")
    private double stopDuration;

    @CommandLine.Option(names = "--capacity", description = "drt legs file", defaultValue = "8")
    private int capacity;


    public static void main(String[] args) {
        new DropOffAnalysis().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Map<String, Map<String, List<Double>>> arrivalsMap = new HashMap<>();
        Map<Integer, MutableInt> arrivalDropsOffDistribution = new HashMap<>();

        for (int i = 1; i < capacity + 1; i++) {
            arrivalDropsOffDistribution.put(i, new MutableInt(0));
        }
        try (CSVParser parser = new CSVParser(Files.newBufferedReader(drtLegsFile),
                CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader())) {
            int counter = 0;
            for (CSVRecord record : parser.getRecords()) {
                String linkId = record.get("toLinkId");
                String vehicleId = record.get("vehicleId");
                double arrivalTime = Double.parseDouble(record.get("arrivalTime"));
                arrivalsMap.computeIfAbsent(vehicleId, m -> new HashMap<>())
                        .computeIfAbsent(linkId, l -> new ArrayList<>()).add(arrivalTime);
                counter++;
            }
            log.info("The trips has been read: there are " + counter + " drt trips");
        }

        int outputCounter = 0;
        for (String vehicleId : arrivalsMap.keySet()) {
            for (List<Double> arrivalTimes : arrivalsMap.get(vehicleId).values()) {
                // Sort the arrival time in ascending order
                arrivalTimes.sort(Comparator.comparingDouble(arrivalTime -> arrivalTime));

                double lastDropOffTime = arrivalTimes.get(0);
                int lastUpdateIdx = 0;

                for (int i = 1; i < arrivalTimes.size(); i++) {
                    double currentDropOffTime = arrivalTimes.get(i);
                    if (currentDropOffTime > lastDropOffTime + stopDuration + 1) {
                        int numberOfDropOffInPreviousStop = i - lastUpdateIdx;
                        lastUpdateIdx = i;
                        arrivalDropsOffDistribution.get(numberOfDropOffInPreviousStop).increment();
                        outputCounter += numberOfDropOffInPreviousStop;
                    }
                    lastDropOffTime = currentDropOffTime;
                }

                int numberOfPassengersAtLastDropOff = arrivalTimes.size() - lastUpdateIdx;
                arrivalDropsOffDistribution.get(numberOfPassengersAtLastDropOff).increment();
                outputCounter += numberOfPassengersAtLastDropOff;
            }
        }

        log.info("in the output there are in total " + outputCounter + " trips");

        CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(directory.toString() + "/analysis-drop-off.tsv"), CSVFormat.TDF);
        csvPrinter.printRecord("number_of_passengers", "count");
        for (int numberOfDropOffs : arrivalDropsOffDistribution.keySet()) {
            int count = arrivalDropsOffDistribution.get(numberOfDropOffs).intValue();
            csvPrinter.printRecord(Integer.toString(numberOfDropOffs), Integer.toString(count));
        }

        csvPrinter.close();

        return 0;
    }
}
