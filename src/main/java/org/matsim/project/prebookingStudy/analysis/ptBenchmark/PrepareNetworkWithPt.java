package org.matsim.project.prebookingStudy.analysis.ptBenchmark;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

public class PrepareNetworkWithPt {
    public static void main(String[] args) {
        String networkPath;
        String ptNetworkPath;
        String outputPath;
        if (args.length != 0) {
            networkPath = args[0];
            ptNetworkPath = args[1];
            outputPath = args[3];
        } else {
            networkPath = "/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/scenarios/input/vulkaneifel-v1.0-network.xml.gz";
            ptNetworkPath = "/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/scenarios/input/pt-files/optimizedNetwork.xml.gz";
            outputPath = "/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/scenarios/input/pt-files/network-with-pt.xml.gz";
        }

        Network network = NetworkUtils.readNetwork(networkPath);
        Network ptNetwork = NetworkUtils.readNetwork(ptNetworkPath);
        modifyNetwork(network, ptNetwork);
        NetworkUtils.writeNetwork(network, outputPath);
    }

    private static void modifyNetwork(Network network, Network ptNetwork) {
        for (Link ptLink : ptNetwork.getLinks().values()) {
            if (ptLink.getAllowedModes().contains(TransportMode.pt)) {
                Node fromNode = ptLink.getFromNode();
                Node toNode = ptLink.getToNode();
                if (!network.getNodes().containsValue(fromNode)) {
                    network.addNode(fromNode);
                }
                if (!network.getNodes().containsValue(toNode)) {
                    network.addNode(toNode);
                }
                network.addLink(ptLink);
            }
        }
    }
}
