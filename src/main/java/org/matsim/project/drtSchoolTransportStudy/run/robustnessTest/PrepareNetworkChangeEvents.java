package org.matsim.project.drtSchoolTransportStudy.run.robustnessTest;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkChangeEventsWriter;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class PrepareNetworkChangeEvents implements MATSimAppCommand {
    @CommandLine.Option(names = "--network", description = "path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--distribution", description = "path to random distribution file", required = true)
    private String distributionFile;

    @CommandLine.Option(names = "--interval", description = "Interval of the network speed changes", defaultValue = "600")
    private double interval;

    @CommandLine.Option(names = "--seed", description = "Random seed", required = true)
    private long seed;

    @CommandLine.Option(names = "--output", description = "output directory of the network change events", required = true)
    private String output;

    public static void main(String[] args) {
        new PrepareNetworkChangeEvents().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        // Read the random distribution generator (a list of double)
        List<Double> randomNumbers = new ArrayList<>();
        try (CSVParser parser = new CSVParser(Files.newBufferedReader(Path.of(distributionFile)),
                CSVFormat.DEFAULT)) {
            for (CSVRecord record : parser.getRecords()) {
                randomNumbers.add(Double.parseDouble(record.get(0)));
            }
        }
        int size = randomNumbers.size();

        Random random = new Random(seed);
        Map<Id<Link>, Double> linkOriginalFreeSpeedMap = new HashMap<>();
        Network network = NetworkUtils.readNetwork(networkPath);

        List<NetworkChangeEvent> networkChangeEvents = new ArrayList<>();

        for (Link link : network.getLinks().values()) {
            linkOriginalFreeSpeedMap.put(link.getId(), link.getFreespeed());
        }

        double startTime = 6 * 3600;
        double endTime = 8 * 3600;

        for (double time = startTime; time < endTime; time += interval) {
            for (Link link : network.getLinks().values()) {
                double factor = randomNumbers.get(random.nextInt(size));
                double newSpeed = linkOriginalFreeSpeedMap.get(link.getId()) / factor;
                NetworkChangeEvent networkChangeEvent = new NetworkChangeEvent(time);
                networkChangeEvent.addLink(link);
                networkChangeEvent.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, newSpeed));
                networkChangeEvents.add(networkChangeEvent);
            }
        }

        NetworkChangeEventsWriter networkChangeEventsWriter = new NetworkChangeEventsWriter();
        networkChangeEventsWriter.write(output + "/network-change-events-" + seed + ".events.xml.gz", networkChangeEvents);

        return 0;
    }
}
