package org.matsim.project.prebookingStudy.jsprit.utils;

import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.EuclideanDistanceCalculator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.core.config.Config;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Time;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatisticCollectorForIterationEndsListener {
    final private Map<Integer, Integer> fleetSizeMap = new HashMap<>();
    final private Map<Integer, Double> costsMap = new HashMap<>();
    public Map<Integer, Integer> getFleetSizeMap() {
        return fleetSizeMap;
    }
    public Map<Integer, Double> getCostsMap() {
        return costsMap;
    }

    final String separator;

    public StatisticCollectorForIterationEndsListener(Config config) {
        this.separator = config.global().getDefaultDelimiter();
    }

    public void writeOutputTrips(String outputFilename) {

        List<String> strList = new ArrayList<String>() {{
            add("iteration");
            add("costs");
            add("fleet_size");
        }};
        String[] tripsHeader = strList.toArray(new String[strList.size()]);

        //ToDo: do not use BufferedWriter
        if (!outputFilename.endsWith("/")) outputFilename = outputFilename + "/";
        try (CSVPrinter tripsCsvPrinter = new CSVPrinter(IOUtils.getBufferedWriter(outputFilename + "iteration_stats.csv"),
                CSVFormat.DEFAULT.withDelimiter(separator.charAt(0)).withHeader(tripsHeader))

        ) {
            for (Map.Entry<Integer, Double> entry : costsMap.entrySet()) {
                List<List<String>> tripRecords = new ArrayList<>();
                for (int i = 0; i < 1; i++) {
                    List<String> tripRecord = new ArrayList<>();
                    tripRecords.add(tripRecord);


                    //add records
                    tripRecord.add(Integer.toString(entry.getKey()));
                    tripRecord.add(Double.toString(entry.getValue()));
                    tripRecord.add(Double.toString(fleetSizeMap.get(entry.getKey())));


                    if (tripsHeader.length != tripRecord.size()) {
                        throw new RuntimeException("TRIPSHEADER.length != tripRecord.size()");
                    }
                }

                tripsCsvPrinter.printRecords(tripRecords);
            }
        } catch (IOException e) {

            e.printStackTrace();
        }

    }
}
