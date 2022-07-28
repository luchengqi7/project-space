package org.matsim.project.drtOperationStudy.scenarioPreparation;

import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NetworkCutter implements MATSimAppCommand {
    @CommandLine.Option(names = "--input", description = "path to input network file", required = true)
    private String inputNetworkFile;

    @CommandLine.Option(names = "--output", description = "path to output network file", required = true)
    private String outputNetworkFile;

    @CommandLine.Mixin
    private ShpOptions shp = new ShpOptions();

    public static void main(String[] args) {
        new NetworkCutter().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Network inputNetwork = NetworkUtils.readNetwork(inputNetworkFile);

        if (!shp.isDefined()) {
            throw new RuntimeException("Shape file is not defined. No cutting can be performed");
        }
        Geometry geometry = shp.getGeometry();
        List<Link> linksToKeep = new ArrayList<>();
        Set<Node> nodesToKeep = new HashSet<>();

        int counter = 0;
        for (Link link : inputNetwork.getLinks().values()) {
            if (!link.getAllowedModes().contains(TransportMode.car)) {
                continue;
            }
            if (MGC.coord2Point(link.getCoord()).within(geometry)) {
                link.setAllowedModes(Set.of(TransportMode.car));
                linksToKeep.add(link);
                nodesToKeep.add(link.getFromNode());
                nodesToKeep.add(link.getToNode());
            }

            counter++;
            if (counter % 10000 == 0) {
                System.out.println("Cutting in progress: " + counter + " links have been processed");
            }
        }

        Network outputNetwork = NetworkUtils.createNetwork();
        for (Node node : nodesToKeep) {
            outputNetwork.addNode(NetworkUtils.createNode(node.getId(), node.getCoord()));
        }
        for (Link link : linksToKeep) {
            outputNetwork.addLink(link);
        }

        var cleaner = new NetworkCleaner();
        cleaner.run(outputNetwork);

        NetworkWriter networkWriter = new NetworkWriter(outputNetwork);
        networkWriter.write(outputNetworkFile);

        return 0;
    }
}
