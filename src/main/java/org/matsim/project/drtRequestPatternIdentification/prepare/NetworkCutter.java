package org.matsim.project.drtRequestPatternIdentification.prepare;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NetworkCutter implements MATSimAppCommand {
    @CommandLine.Option(names = "--network", description = "path to network file", required = true)
    private String networkPath;

    @CommandLine.Option(names = "--output", description = "path to output network", required = true)
    private String outputNetwork;

    @CommandLine.Option(names = "--pt", description = "keep PT links", defaultValue = "false")
    private boolean keepPT;

    @CommandLine.Mixin
    private ShpOptions shp = new ShpOptions();

    public static void main(String[] args) {
        new NetworkCutter().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Network network = NetworkUtils.readNetwork(networkPath);
        Geometry areaToKeep = shp.getGeometry();

        // Remove irrelevant links
        List<Link> linksToRemove = new ArrayList<>();
        System.out.println("Total links" + network.getLinks().size());
        int counter = 0;
        for (Link link : network.getLinks().values()) {
            counter++;
            if (counter % 1000 == 0) {
                System.out.println("Processing: " + counter + " processed.");
            }

            if (link.getAllowedModes().contains(TransportMode.pt)) {
                if (!keepPT) {
                    linksToRemove.add(link);
                }
                continue;
            }

            Point from = MGC.coord2Point(link.getFromNode().getCoord());
            Point to = MGC.coord2Point(link.getToNode().getCoord());
            if (!from.within(areaToKeep) || !to.within(areaToKeep)) {
                linksToRemove.add(link);
            }
        }

        for (Link link : linksToRemove) {
            network.removeLink(link.getId());
        }

        // Remove empty nodes
        Set<Node> nodesToRemove = new HashSet<>();
        for (Node node : network.getNodes().values()) {
            if (node.getInLinks().isEmpty() && node.getOutLinks().isEmpty()) {
                nodesToRemove.add(node);
            }
        }
        for (Node node : nodesToRemove) {
            network.removeNode(node.getId());
        }

        // Clean the network
        MultimodalNetworkCleaner networkCleaner = new MultimodalNetworkCleaner(network);
        networkCleaner.run(Set.of(TransportMode.car, TransportMode.pt));
        NetworkUtils.writeNetwork(network, outputNetwork);
        return 0;
    }
}
