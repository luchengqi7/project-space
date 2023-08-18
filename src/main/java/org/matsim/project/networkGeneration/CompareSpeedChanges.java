package org.matsim.project.networkGeneration;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;

public class CompareSpeedChanges implements MATSimAppCommand {
    @CommandLine.Option(names = "--before", description = "path to network before change", required = true)
    private String inputNetwork;

    @CommandLine.Option(names = "--after", description = "path to network after change", required = true)
    private String networkAfterChange;

    public static void main(String[] args) {
        new CompareSpeedChanges().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Network networkBefore = NetworkUtils.readNetwork(inputNetwork);
        Network networkAfter = NetworkUtils.readNetwork(networkAfterChange);

        for (Id<Link> linkId : networkBefore.getLinks().keySet()) {
            double speedBefore = networkBefore.getLinks().get(linkId).getFreespeed();
            double speedAfter = networkAfter.getLinks().get(linkId).getFreespeed();
            double difference = speedAfter - speedBefore;
            double relative = speedAfter / speedBefore - 1;
            networkAfter.getLinks().get(linkId).getAttributes().putAttribute("speed_change", difference);
            networkAfter.getLinks().get(linkId).getAttributes().putAttribute("speed_change_relative", relative);
        }

        NetworkUtils.writeNetwork(networkAfter, networkAfterChange);
        return 0;
    }
}
