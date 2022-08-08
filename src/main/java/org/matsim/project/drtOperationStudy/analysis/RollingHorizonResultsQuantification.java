package org.matsim.project.drtOperationStudy.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;
import org.matsim.contrib.drt.util.DrtEventsReaders;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEventHandler;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.matsim.application.ApplicationUtils.globFile;

public class RollingHorizonResultsQuantification {
    private final VehicleDrivingTimeStatistics vehicleDrivingTimeStatistics = new VehicleDrivingTimeStatistics();
    private final RejectionStatistics rejectionStatistics = new RejectionStatistics();
    private String computationalTimeString = "unknown";

    /**
     * Offline post analysis, to be called from the main method of this class.
     */
    private void analyze(String eventsFilePathString) {
        vehicleDrivingTimeStatistics.reset(0);
        rejectionStatistics.reset(0);

        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(vehicleDrivingTimeStatistics);
        eventsManager.addHandler(rejectionStatistics);
        MatsimEventsReader eventsReader = DrtEventsReaders.createEventsReader(eventsManager, WaitForStopTask.TYPE);
        eventsReader.readFile(eventsFilePathString);
    }

    /**
     * Online post analysis, to be attached to the run script
     */
    public void analyze(Path outputDirectory, long computationalTime) {
        vehicleDrivingTimeStatistics.reset(0);
        rejectionStatistics.reset(0);

        computationalTimeString = Long.toString(computationalTime);
        Path eventPath = globFile(outputDirectory, "*output_events.*");
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(vehicleDrivingTimeStatistics);
        eventsManager.addHandler(rejectionStatistics);
        MatsimEventsReader eventsReader = DrtEventsReaders.createEventsReader(eventsManager, WaitForStopTask.TYPE);
        eventsReader.readFile(eventPath.toString());
    }

    public double getTotalDrivingTime() {
        return vehicleDrivingTimeStatistics.getTotalDrivingTime();
    }

    public int getRejections() {
        return rejectionStatistics.getRejectedRequests();
    }

    public void writeResults(Path outputDirectory) throws IOException {
        Path outputStatsPath = Path.of(outputDirectory + "/drt-result-quantification.tsv");
        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(outputStatsPath.toString()), CSVFormat.TDF);
        List<String> titleRow = Arrays.asList("total_driving_time", "rejections", "computationalTime");
        tsvWriter.printRecord(titleRow);
        tsvWriter.printRecord(Arrays.asList(Double.toString(getTotalDrivingTime()), Long.toString(getRejections()), computationalTimeString));
        tsvWriter.close();
        System.out.println("Computational time = " + computationalTimeString);
        System.out.println("Total driving time = " + getTotalDrivingTime());
        System.out.println("Number of rejections = " + getRejections());
    }

    public static void main(String[] args) {
        String eventPath = args[0];
        RollingHorizonResultsQuantification rollingHorizonResultsQuantification = new RollingHorizonResultsQuantification();
        rollingHorizonResultsQuantification.analyze(eventPath);
        System.out.println("Total driving time is " + rollingHorizonResultsQuantification.getTotalDrivingTime() + " seconds");
        System.out.println("There are " + rollingHorizonResultsQuantification.getRejections() + " rejected requests");
    }

    /**
     * Read total fleet driving time from the event file
     */
    static class VehicleDrivingTimeStatistics implements VehicleEntersTrafficEventHandler,
            VehicleLeavesTrafficEventHandler {
        private double totalDrivingTime;

        @Override
        public void reset(int iteration) {
            totalDrivingTime = 0;
        }

        @Override
        public void handleEvent(VehicleEntersTrafficEvent vehicleEntersTrafficEvent) {
            double enterTime = vehicleEntersTrafficEvent.getTime();
            totalDrivingTime -= enterTime;
        }

        @Override
        public void handleEvent(VehicleLeavesTrafficEvent vehicleLeavesTrafficEvent) {
            double leavingTime = vehicleLeavesTrafficEvent.getTime();
            totalDrivingTime += leavingTime;
        }

        public double getTotalDrivingTime() {
            return totalDrivingTime;
        }
    }

    static class RejectionStatistics implements PassengerRequestRejectedEventHandler {
        private int rejectedRequests = 0;

        @Override
        public void handleEvent(PassengerRequestRejectedEvent passengerRequestRejectedEvent) {
            rejectedRequests++;
        }

        @Override
        public void reset(int iteration) {
            rejectedRequests = 0;
        }

        public int getRejectedRequests() {
            return rejectedRequests;
        }
    }


}
