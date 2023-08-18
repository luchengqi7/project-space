package org.matsim.project.networkGeneration;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.core.network.NetworkUtils;

public class ProcessManhattanNetwork {
    public static void main(String[] args) {
        // 1st input argument: input network; 2nd input argument: output network
        assert args.length == 2;
        Network network = NetworkUtils.readNetwork(args[0]);
        // Fix the free speed
        // motorway, trunk, primary: speed limit = 40 mph --> 17.8 m/s
        // others speed limit = 25 mph --> free speed = (25 / 1.6) * 0.9 = 10.0584

        for (Link link : network.getLinks().values()) {
            String type = link.getAttributes().getAttribute("type").toString();
            if (type != null) {
                if (type.equals(OsmTags.MOTORWAY) || type.equals(OsmTags.TRUNK) ||
                        type.equals(OsmTags.MOTORWAY_LINK) || type.equals(OsmTags.TRUNK_LINK)) {
                    if (link.getFreespeed() > 17.8) {
                        link.setFreespeed(17.8);
                    }
                } else {
                    if (link.getFreespeed() > 10.0584) {
                        link.setFreespeed(10.0584);
                    }
                }
            }
        }

        NetworkWriter networkWriter = new NetworkWriter(network);
        networkWriter.write(args[1]);
    }
}
