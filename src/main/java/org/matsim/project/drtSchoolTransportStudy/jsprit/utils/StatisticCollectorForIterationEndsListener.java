package org.matsim.project.drtSchoolTransportStudy.jsprit.utils;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.core.config.Config;
import org.matsim.core.utils.io.IOUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatisticCollectorForIterationEndsListener {

    final private static Map<Integer, Integer> fleetSizeMap = new HashMap<>();
    final private static Map<Integer, Double> costsMap = new HashMap<>();
    public static Map<Integer, Integer> getFleetSizeMap() {
        return fleetSizeMap;
    }
    public static Map<Integer, Double> getCostsMap() {
        return costsMap;
    }

    final private static Map<Integer, Integer> bestFleetSizeMap = new HashMap<>();
    final private static Map<Integer, Double> bestCostsMap = new HashMap<>();
    public static Map<Integer, Integer> getBestFleetSizeMap() {
        return bestFleetSizeMap;
    }
    public static Map<Integer, Double> getBestCostsMap() {
        return bestCostsMap;
    }

    private static int bestFleetSize;
    public static void setBestFleetSize(int bestFleetSize) {
        StatisticCollectorForIterationEndsListener.bestFleetSize = bestFleetSize;
    }
    public static int getBestFleetSize() {
        return bestFleetSize;
    }

    private static double bestCosts;
    public static void setBestCosts(double bestCosts) {
        StatisticCollectorForIterationEndsListener.bestCosts = bestCosts;
    }
    public static double getBestCosts() {
        return bestCosts;
    }

    final String separator;

    public StatisticCollectorForIterationEndsListener(Config config) {
        this.separator = config.global().getDefaultDelimiter();
    }

    public void writeOutputStats(String outputFilename) {

        List<String> strList = new ArrayList<String>() {{
            add("iteration");
            add("costs");
            add("fleet_size");
            add("best_costs");
            add("best_fleet_size");
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
                    tripRecord.add(Integer.toString(fleetSizeMap.get(entry.getKey())));
                    tripRecord.add(Double.toString(bestCostsMap.get(entry.getKey())));
                    tripRecord.add(Integer.toString(bestFleetSizeMap.get(entry.getKey())));


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
