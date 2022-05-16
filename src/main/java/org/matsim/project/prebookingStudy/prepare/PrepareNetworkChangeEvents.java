package org.matsim.project.prebookingStudy.prepare;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkChangeEventsWriter;
import picocli.CommandLine;

import java.util.*;

public class PrepareNetworkChangeEvents implements MATSimAppCommand {
    @CommandLine.Option(names = "--network", description = "path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--std", description = "Standard deviation of the network speed change", defaultValue = "0.05")
    private double standardDeviation;

    @CommandLine.Option(names = "--interval", description = "Interval of the network speed changes", defaultValue = "600")
    private double interval;

    @CommandLine.Option(names = "--output", description = "Output file of the network change event", required = true)
    private String output;

    public static void main(String[] args) {
        new PrepareNetworkChangeEvents().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Random random = new Random(1234);
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
                double factor = 1 + random.nextGaussian() * standardDeviation;
                double newSpeed = linkOriginalFreeSpeedMap.get(link.getId()) * factor;
                NetworkChangeEvent networkChangeEvent = new NetworkChangeEvent(time);
                networkChangeEvent.addLink(link);
                networkChangeEvent.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, newSpeed));
                // TODO does the network change event change the link property of the network?

                networkChangeEvents.add(networkChangeEvent);
            }
        }

        NetworkChangeEventsWriter networkChangeEventsWriter = new NetworkChangeEventsWriter();
        networkChangeEventsWriter.write(output, networkChangeEvents);

        return 0;
    }
}
