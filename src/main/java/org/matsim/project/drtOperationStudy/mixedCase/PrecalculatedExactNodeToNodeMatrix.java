package org.matsim.project.drtOperationStudy.mixedCase;

import one.util.streamex.EntryStream;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.zone.Zone;
import org.matsim.contrib.zone.skims.Matrix;
import org.matsim.contrib.zone.skims.TravelTimeMatrices;
import org.matsim.contrib.zone.skims.TravelTimeMatrix;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import java.util.*;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static org.matsim.contrib.dvrp.path.VrpPaths.FIRST_LINK_TT;

class PrecalculatedExactNodeToNodeMatrix implements TravelTimeMatrix {
    private final Network network;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;
    private final TravelDisutility travelDisutility;
    private Matrix nodeToNodeMatrix;
    private final Map<Node, Zone> zoneByNode;

    private final Set<Id<Link>> linkIds = new HashSet<>();
    private final Map<Node, Map<Node, Double>> independentNodeToNodeTravelTimeMap = new HashMap<>();

    PrecalculatedExactNodeToNodeMatrix(Network network, TravelTime travelTime, double time, Population population) {
        this.network = network;
        this.travelTime = travelTime;
        this.travelDisutility = new TimeAsTravelDisutility(travelTime);
        this.router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);

        // calculate travel time matrix
        readRelevantLocations(population);
        zoneByNode = linkIds
                .stream()
                .flatMap(linkId -> Stream.of(network.getLinks().get(linkId).getFromNode(), network.getLinks().get(linkId).getToNode()))
                .collect(toMap(n -> n, node -> new Zone(Id.create(node.getId(), Zone.class), "node", node.getCoord()),
                        (zone1, zone2) -> zone1));

        var nodeByZone = EntryStream.of(zoneByNode).invert().toMap();

        nodeToNodeMatrix = TravelTimeMatrices.calculateTravelTimeMatrix(network, nodeByZone, time, travelTime,
                travelDisutility, Runtime.getRuntime().availableProcessors());
    }

    void updateMatrix(double time) {
        var nodeByZone = EntryStream.of(zoneByNode).invert().toMap();
        nodeToNodeMatrix = TravelTimeMatrices.calculateTravelTimeMatrix(network, nodeByZone, time, travelTime,
                travelDisutility, Runtime.getRuntime().availableProcessors());
    }

    @Override
    public int getTravelTime(Node fromNode, Node toNode, double departureTime) {
        if (zoneByNode.containsKey(fromNode) && zoneByNode.containsKey(toNode)) {
            return nodeToNodeMatrix.get(zoneByNode.get(fromNode), zoneByNode.get(toNode));
        }

        double travelTime = independentNodeToNodeTravelTimeMap.
                computeIfAbsent(fromNode, f -> new HashMap<>()).
                computeIfAbsent(toNode, t -> router.calcLeastCostPath(fromNode, toNode, departureTime, null, null).travelTime);
        return (int) travelTime;
    }

    private void readRelevantLocations(Population population) {
        MainModeIdentifier modeIdentifier = new DefaultAnalysisMainModeIdentifier();
        for (Person person : population.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                if (modeIdentifier.identifyMainMode(trip.getTripElements()).equals(TransportMode.drt)) {
                    linkIds.add(trip.getOriginActivity().getLinkId());
                    linkIds.add(trip.getDestinationActivity().getLinkId());
                }
            }
        }
    }
}
