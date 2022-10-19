package org.matsim.project.drtOperationStudy.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter;
import org.matsim.contrib.drt.schedule.DrtTaskType;
import org.matsim.contrib.drt.util.DrtEventsReaders;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Optional;

@Deprecated
public class UpdatedDrtTaskWriter {
//    private static final Logger log = LogManager.getLogger(DrtVehicleStoppingTaskWriter.class);
//
//    private final String eventsFile;
//    private final String networkFile;
//    private final String stoppingTasksOutputPath;
//
//    public UpdatedDrtTaskWriter(Path directory) {
//        Path eventsPath = glob(directory, "*output_events*").orElseThrow(() -> new IllegalStateException("No events file found."));
//        Path networkPath = glob(directory, "*output_network*").orElseThrow(() -> new IllegalStateException("No network file found."));
//        this.eventsFile = eventsPath.toString();
//        this.networkFile = networkPath.toString();
//        this.stoppingTasksOutputPath = directory + "/drt-stopping-tasks-XY-plot.csv";
//    }
//
//    public static void main(String[] args) throws IOException {
//        // Input in argument: directory of the simulation output
//        DrtVehicleStoppingTaskWriter drtVehicleStoppingTaskWriter = new DrtVehicleStoppingTaskWriter(Path.of(args[0]));
//        drtVehicleStoppingTaskWriter.run();
//    }
//
//    public void run(DrtTaskType... nonStandardTaskTypes) throws IOException {
//        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
//        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile);
//        Network network = scenario.getNetwork();
//        DrtStoppingTasksRecorderWithWaitForStop drtStoppingTasksRecorderWithWaitForStop = new DrtStoppingTasksRecorderWithWaitForStop();
//
//        EventsManager eventManager = EventsUtils.createEventsManager();
//        eventManager.addHandler(drtStoppingTasksRecorderWithWaitForStop);
//        eventManager.initProcessing();
//
//        MatsimEventsReader matsimEventsReader = DrtEventsReaders.createEventsReader(eventManager, nonStandardTaskTypes);
//        matsimEventsReader.readFile(eventsFile);
//        eventManager.finishProcessing();
//
//        List<DrtStoppingTasksRecorderWithWaitForStop.DrtTaskInformation> drtStoppingTaskEntries = drtStoppingTasksRecorderWithWaitForStop.getDrtTasksEntries();
//
//        System.out.println("There are " + drtStoppingTaskEntries.size() + " drt stopping tasks in total");
//
//        writeResultIntoCSVFile(drtStoppingTaskEntries, network, stoppingTasksOutputPath);
//    }
//
//    private void writeResultIntoCSVFile(List<DrtStoppingTasksRecorderWithWaitForStop.DrtTaskInformation> drtStoppingTaskEntries, Network network, String outputFile)
//            throws IOException {
//        System.out.println("Writing CSV File now");
//        FileWriter csvWriter = new FileWriter(outputFile);
//
//        csvWriter.append("Task_name");
//        csvWriter.append(",");
//        csvWriter.append("X");
//        csvWriter.append(",");
//        csvWriter.append("Y");
//        csvWriter.append(",");
//        csvWriter.append("Start_time");
//        csvWriter.append(",");
//        csvWriter.append("End_time");
//        csvWriter.append(",");
//        csvWriter.append("Driver_id");
//        csvWriter.append(",");
//        csvWriter.append("Vehicle_occupancy");
//        csvWriter.append("\n");
//
//        for (DrtStoppingTasksRecorderWithWaitForStop.DrtTaskInformation drtStoppingTaskDataEntry : drtStoppingTaskEntries) {
//            double X = network.getLinks().get(drtStoppingTaskDataEntry.getLinkId()).getToNode().getCoord().getX();
//            double Y = network.getLinks().get(drtStoppingTaskDataEntry.getLinkId()).getToNode().getCoord().getY();
//            csvWriter.append(drtStoppingTaskDataEntry.getTaskName());
//            csvWriter.append(",");
//            csvWriter.append(Double.toString(X));
//            csvWriter.append(",");
//            csvWriter.append(Double.toString(Y));
//            csvWriter.append(",");
//            csvWriter.append(Double.toString(drtStoppingTaskDataEntry.getStartTime()));
//            csvWriter.append(",");
//            csvWriter.append(Double.toString(drtStoppingTaskDataEntry.getEndTime()));
//            csvWriter.append(",");
//            csvWriter.append(drtStoppingTaskDataEntry.getVehicleId().toString());
//            csvWriter.append(",");
//            csvWriter.append(Integer.toString(drtStoppingTaskDataEntry.getOccupancy()));
//            csvWriter.append("\n");
//        }
//        csvWriter.flush();
//        csvWriter.close();
//    }
//
//    /**
//     * Glob pattern from path, if not found tries to go into the parent directory.
//     */
//    public static Optional<Path> glob(Path path, String pattern) {
//        PathMatcher m = path.getFileSystem().getPathMatcher("glob:" + pattern);
//        try {
//            Optional<Path> match = Files.list(path).filter(p -> m.matches(p.getFileName())).findFirst();
//            // Look one directory higher for required file
//            if (match.isEmpty())
//                return Files.list(path.getParent()).filter(p -> m.matches(p.getFileName())).findFirst();
//
//            return match;
//        } catch (IOException e) {
//            log.warn(e);
//        }
//
//        return Optional.empty();
//    }
}
