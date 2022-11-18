package org.matsim.project.drtOperationStudy.mixedCase;

import one.util.streamex.EntryStream;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.contrib.zone.Zone;
import org.matsim.contrib.zone.skims.TravelTimeMatrices;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static org.matsim.contrib.dvrp.path.VrpPaths.FIRST_LINK_TT;

class LinkToLinkTravelTimeMatrix {
    private final int[][] matrix;
    private final Network network;
    private final Map<Id<Link>, Integer> linkIdToIndexMap;
    private final TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
    private final LeastCostPathCalculator router;

    LinkToLinkTravelTimeMatrix(Map<Id<Link>, Integer> linkIdToIndexMap, Network network, double time) {
        int size = linkIdToIndexMap.size();
        this.matrix = new int[size][size];
        this.linkIdToIndexMap = linkIdToIndexMap;
        this.network = network;
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        this.router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);

        // calculate travel time matrix
        var zoneByNode = linkIdToIndexMap.keySet()
                .stream()
                .flatMap(linkId -> Stream.of(network.getLinks().get(linkId).getFromNode(), network.getLinks().get(linkId).getToNode()))
                .collect(toMap(n -> n, node -> new Zone(Id.create(node.getId(), Zone.class), "node", node.getCoord()),
                        (zone1, zone2) -> zone1));

        var nodeByZone = EntryStream.of(zoneByNode).invert().toMap();

        var nodeToNodeMatrix = TravelTimeMatrices.calculateTravelTimeMatrix(network, nodeByZone, time, travelTime,
                travelDisutility, Runtime.getRuntime().availableProcessors());

        for (var from : linkIdToIndexMap.keySet()) {
            var fromLink = network.getLinks().get(from);
            var fromZone = zoneByNode.get(fromLink.getToNode()); // we start from the link's TO node
            var fromLocationIdx = linkIdToIndexMap.get(from);

            for (var to : linkIdToIndexMap.keySet()) {
                var toLink = network.getLinks().get(to);
                var toZone = zoneByNode.get(toLink.getFromNode()); // we finish at the link's FROM node
                var toLocationIdx = linkIdToIndexMap.get(to);

                if (fromLink != toLink) { // otherwise, the matrix cell remains set to 0
                    double duration = FIRST_LINK_TT + nodeToNodeMatrix.get(fromZone, toZone) + VrpPaths.getLastLinkTT(
                            travelTime, toLink, time);
                    matrix[fromLocationIdx][toLocationIdx] = (int) duration;
                }
            }
        }
    }

    int getLinkToLinkTravelTime(Id<Link> fromLinkId, Id<Link> toLinkId) {
        // If the matrix contains the link-to-link pair, then return the value directly
        if (linkIdToIndexMap.containsKey(fromLinkId) && linkIdToIndexMap.containsKey(toLinkId)) {
            return matrix[linkIdToIndexMap.get(fromLinkId)][linkIdToIndexMap.get(toLinkId)];
        }

        // Otherwise, calculate the travel time
        // TODO maybe consider store/cache those values somewhere?
        Link fromLink = network.getLinks().get(fromLinkId);
        Link toLink = network.getLinks().get(toLinkId);
        return (int) VrpPaths.calcAndCreatePath(fromLink, toLink, 0, router, travelTime).getTravelTime();
    }

    int[][] getMatrix() {
        return matrix;
    }
}
