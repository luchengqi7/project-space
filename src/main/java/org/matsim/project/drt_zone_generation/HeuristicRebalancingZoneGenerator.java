package org.matsim.project.drt_zone_generation;

import org.apache.commons.lang.mutable.MutableInt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.drt.analysis.zonal.DrtZonalSystem;
import org.matsim.contrib.drt.analysis.zonal.DrtZone;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.contrib.zone.skims.SparseMatrix;
import org.matsim.contrib.zone.skims.TravelTimeMatrices;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.project.utils.ProgressPrinter;

import java.util.*;

public class HeuristicRebalancingZoneGenerator {
    private final Network network;
    private final String outputNetworkWithZonesPath;
    private final double timeRadius;
    private final Map<Id<Node>, Set<Id<Link>>> reachableLinksMap = new HashMap<>();
    private final SparseMatrix freeSpeedTravelTimeSparseMatrix;
    private final Set<Id<Link>> linksTobeCovered = new HashSet<>();
    private final Map<Id<Link>, MutableInt> departuresMap = new HashMap<>();
    private final int zoneGenerationIterations;
    private final List<String> consideredTripModes;
    private final Map<Id<Node>, List<Link>> zonalSystemData = new LinkedHashMap<>();

    private static final Logger log = LogManager.getLogger(HeuristicRebalancingZoneGenerator.class);

    public static void main(String[] args) {
        // TODO currently only support car only network (need to use mode-specific subnetwork in the normal MATSim simulations)
        // TODO convert to MATSim command line input style
        // Current input arguments: input network path, output network (for visualization) path,
        // optional number of zone improvement iterations, optional population path
        Network inputNetwork = NetworkUtils.readNetwork(args[0]);
        String visualisationNetworkPath = args[1];
        int zoneGenerationIterations = args[2] == null ? 0 : Integer.parseInt(args[2]);
        Population inputPlans = args.length == 4 ? PopulationUtils.readPopulation(args[3]) : null;

        HeuristicRebalancingZoneGenerator heuristicRebalancingZoneGenerator = new ZoneGeneratorBuilder(inputNetwork, visualisationNetworkPath)
                .setInputPlans(inputPlans)
                .setZoneIterations(zoneGenerationIterations)
                .build();
        heuristicRebalancingZoneGenerator.compute();
    }

    public DrtZonalSystem compute() {
        analyzeNetwork();
        selectInitialCentroids();
        generateZones();
        writeOutputNetworkWithZones();
        return exportDrtZonalSystems();
    }

    public static class ZoneGeneratorBuilder {
        private final Network network;
        // required
        private final String outputNetworkWithZonesPath;
        // required
        private Population inputPlans = null;
        private double timeRadius = 300;
        private double sparseMatrixMaxDistance = 10000;
        private TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        private TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        private int zoneIterations = 0;
        private List<String> consideredTripModes = Arrays.asList(TransportMode.drt, TransportMode.car,
                TransportMode.pt, TransportMode.bike, TransportMode.walk);

        public ZoneGeneratorBuilder(Network network, String outputNetworkWithZonesPath) {
            this.network = network;
            this.outputNetworkWithZonesPath = outputNetworkWithZonesPath;
        }


        public ZoneGeneratorBuilder setInputPlans(Population inputPlans) {
            this.inputPlans = inputPlans;
            return this;
        }

        public ZoneGeneratorBuilder setTimeRadius(double timeRadius) {
            this.timeRadius = timeRadius;
            return this;
        }

        public ZoneGeneratorBuilder setSparseMatrixMaxDistance(double sparseMatrixMaxDistance) {
            this.sparseMatrixMaxDistance = sparseMatrixMaxDistance;
            return this;
        }

        public ZoneGeneratorBuilder setTravelTime(TravelTime travelTime) {
            this.travelTime = travelTime;
            return this;
        }

        public ZoneGeneratorBuilder setTravelDisutility(TravelDisutility travelDisutility) {
            this.travelDisutility = travelDisutility;
            return this;
        }

        public ZoneGeneratorBuilder setZoneIterations(int zoneIterations) {
            this.zoneIterations = zoneIterations;
            return this;
        }

        public ZoneGeneratorBuilder setConsideredTripModes(List<String> consideredTripModes) {
            this.consideredTripModes = consideredTripModes;
            return this;
        }

        public HeuristicRebalancingZoneGenerator build() {
            SparseMatrix freeSpeedTravelTimeSparseMatrix = TravelTimeMatrices.calculateTravelTimeSparseMatrix(network,
                    sparseMatrixMaxDistance, 0, travelTime, travelDisutility,
                    Runtime.getRuntime().availableProcessors());
            return new HeuristicRebalancingZoneGenerator(network, outputNetworkWithZonesPath, timeRadius,
                    freeSpeedTravelTimeSparseMatrix, inputPlans, zoneIterations, consideredTripModes);
        }
    }

    private HeuristicRebalancingZoneGenerator(Network network, String outputNetworkWithZonesPath,
                                              double timeRadius, SparseMatrix sparseMatrix, Population inputPlans,
                                              int zoneGenerationIterations, List<String> consideredTripModes) {
        this.network = network;
        this.outputNetworkWithZonesPath = outputNetworkWithZonesPath;
        this.timeRadius = timeRadius;
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        this.freeSpeedTravelTimeSparseMatrix = sparseMatrix;
        this.zoneGenerationIterations = zoneGenerationIterations;
        this.consideredTripModes = consideredTripModes;
        processPlans(inputPlans);
    }

    public void processPlans(Population inputPlans) {
        if (inputPlans != null) {
            MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
            for (Person person : inputPlans.getPersons().values()) {
                for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                    if (consideredTripModes.contains(mainModeIdentifier.identifyMainMode(trip.getTripElements()))) {
                        Id<Link> fromLinkId = trip.getOriginActivity().getLinkId();
                        if (fromLinkId == null) {
                            fromLinkId = NetworkUtils.getNearestLink(network, trip.getOriginActivity().getCoord()).getId();
                        }
                        linksTobeCovered.add(fromLinkId);
                        departuresMap.computeIfAbsent(fromLinkId, linkId -> new MutableInt()).increment();

                        Id<Link> toLinkId = trip.getDestinationActivity().getLinkId();
                        if (toLinkId == null) {
                            toLinkId = NetworkUtils.getNearestLink(network, trip.getDestinationActivity().getCoord()).getId();
                        }
                        linksTobeCovered.add(toLinkId);
                    }
                }
            }
        }

        if (linksTobeCovered.isEmpty()) {
            log.info("No valid population file is provided. Therefore, all links are to be covered.");
            linksTobeCovered.addAll(network.getLinks().keySet());
            log.info("All the " + linksTobeCovered.size() + " links on the network are to be covered");
        } else {
            log.info("Population file is provided. We will only cover the links with at least one departure or arrival");
            log.info("There are " + linksTobeCovered.size() + " (out of " + network.getLinks().size() + ") links to be covered. ");
        }
    }


    public void analyzeNetwork() {
        // Explore reachable links
        log.info("Begin analyzing network. This may take some time...");
        int numOfNodesInNetwork = network.getNodes().size();
        ProgressPrinter networkAnalysisCounter = new ProgressPrinter("Network analysis", numOfNodesInNetwork);

        network.getNodes().keySet().forEach(nodeId -> reachableLinksMap.put(nodeId, new HashSet<>()));
        for (Node node1 : network.getNodes().values()) {
            // All outgoing links from this node should be considered reachable
            node1.getOutLinks().values().stream()
                    .filter(link -> linksTobeCovered.contains(link.getId()))
                    .forEach(link -> reachableLinksMap.get(node1.getId()).add(link.getId()));
            for (Node node2 : network.getNodes().values()) {
                // if same node, then skip
                if (node1.getId().toString().equals(node2.getId().toString())) {
                    continue;
                }

                double node1ToNode2TravelTime = freeSpeedTravelTimeSparseMatrix.get(node1, node2);
                // if the node 2 is too far away from node 1, then skip
                if (node1ToNode2TravelTime == -1 || node1ToNode2TravelTime > timeRadius) {
                    // note: -1 means not even recorded in the sparse matrix --> too far away
                    continue;
                }

                // if we reach here, node 2 is reachable from node 1. Then, we check each outgoing links from node 2
                for (Link link : node2.getOutLinks().values()) {
                    if (linksTobeCovered.contains(link.getId())) {
                        double linkTravelTime = Math.floor(link.getLength() / link.getFreespeed()) + 1;
                        if (2 + node1ToNode2TravelTime + linkTravelTime <= timeRadius) {
                            // above is how VRP travel time calculated
                            reachableLinksMap.get(node1.getId()).add(link.getId());
                        }
                    }
                }
            }
            // node1 is analyzed, move on to next node
            networkAnalysisCounter.countUp();
        }
    }

    public void selectInitialCentroids() {
        log.info("Begin selecting centroids. This may take some time...");
        int totalLinksToCover = linksTobeCovered.size();
        ProgressPrinter centroidSelectionPrinter = new ProgressPrinter("Initial centroid selection", totalLinksToCover);

        // Copy the reachable links map，including copying the sets in the values of the map
        Map<Id<Node>, Set<Id<Link>>> newlyCoveredLinksMap = createReachableLInksMapCopy();

        // Initialize uncovered links
        Set<Id<Link>> uncoveredLinkIds = new HashSet<>(linksTobeCovered);
        while (!uncoveredLinkIds.isEmpty()) {
            // score the links
            Map<Id<Node>, Double> nodesScoresMap = scoreTheNodes(newlyCoveredLinksMap);

            // choose the centroid based on score map
            Id<Node> selectedNodeId = selectBasedOnScoreMap(nodesScoresMap);

            // add that node to the zonal system
            zonalSystemData.put(selectedNodeId, new ArrayList<>());

            // remove all the newly covered links from the uncoveredLinkIds
            uncoveredLinkIds.removeAll(reachableLinksMap.get(selectedNodeId));

            // update the newlyCoveredLinksMap by removing links that are already covered
            for (Id<Node> nodeId : newlyCoveredLinksMap.keySet()) {
                newlyCoveredLinksMap.get(nodeId).removeAll(reachableLinksMap.get(selectedNodeId));
            }

            // Print the progress
            int numLinksAlreadyCovered = totalLinksToCover - uncoveredLinkIds.size();
            centroidSelectionPrinter.countTo(numLinksAlreadyCovered);
        }

        log.info("Potential centroids identified. There are in total " + zonalSystemData.size() + " potential centroid points");
        // remove redundant centroid
        removeRedundantCentroid();
    }

    private Id<Node> selectBasedOnScoreMap(Map<Id<Node>, Double> nodesScoresMap) {
        // Current implementation: Simply choose the link with best score
        Id<Node> selectedNodeId = null;
        double bestScore = 0;
        for (Id<Node> nodeId : nodesScoresMap.keySet()) {
            if (nodesScoresMap.get(nodeId) > bestScore) {
                bestScore = nodesScoresMap.get(nodeId);
                selectedNodeId = nodeId;
            }
        }
        return selectedNodeId;
    }

    private Map<Id<Node>, Double> scoreTheNodes(Map<Id<Node>, Set<Id<Link>>> newlyCoveredLinksMap) {
        // Current implementation: simply count the number of newly covered links
        Map<Id<Node>, Double> nodeScoresMap = new HashMap<>();
        for (Node node : network.getNodes().values()) {
            Set<Id<Link>> newlyCoveredLinkIds = newlyCoveredLinksMap.get(node.getId());
            double score = newlyCoveredLinkIds.size();
            nodeScoresMap.put(node.getId(), score);
        }
        return nodeScoresMap;
    }

    private Map<Id<Node>, Set<Id<Link>>> createReachableLInksMapCopy() {
        Map<Id<Node>, Set<Id<Link>>> reachableLinksMapCopy = new HashMap<>();
        for (Id<Node> nodeId : network.getNodes().keySet()) {
            reachableLinksMapCopy.put(nodeId, new HashSet<>(reachableLinksMap.get(nodeId)));
        }
        return reachableLinksMapCopy;
    }

    private void removeRedundantCentroid() {
        // Find all redundant centroids
        log.info("Checking for redundant centroids");
        Set<Id<Node>> redundantCentroids = identifyRedundantCentroids();
        log.info("Number of redundant centroids identified = " + redundantCentroids.size());

        // Remove the redundant centroid that covers the minimum number of links
        while (!redundantCentroids.isEmpty()) {
            int minReachableLinks = Integer.MAX_VALUE;
            Id<Node> centroidToRemove = null;
            for (Id<Node> redundantCentroid : redundantCentroids) {
                int numReachableLinks = reachableLinksMap.get(redundantCentroid).size();
                if (numReachableLinks < minReachableLinks) {
                    minReachableLinks = numReachableLinks;
                    centroidToRemove = redundantCentroid;
                }
            }
            zonalSystemData.remove(centroidToRemove);

            // update redundant centroids set
            redundantCentroids = identifyRedundantCentroids();
            log.info("Removing in progress: " + redundantCentroids.size() + " redundant centroids (i.e., zones) left");
        }
        log.info("After removal, there are " + zonalSystemData.size() + " centroids (i.e., zones) remaining");
    }

    protected Set<Id<Node>> identifyRedundantCentroids() {
        Set<Id<Node>> redundantCentroids = new HashSet<>();
        for (Id<Node> centroidNodeId : zonalSystemData.keySet()) {
            Set<Id<Link>> uniqueReachableLinkIds = new HashSet<>(reachableLinksMap.get(centroidNodeId));
            for (Id<Node> anotherCentriodNodeId : zonalSystemData.keySet()) {
                if (centroidNodeId.toString().equals(anotherCentriodNodeId.toString())) {
                    // skip itself
                    continue;
                }
                uniqueReachableLinkIds.removeAll(reachableLinksMap.get(anotherCentriodNodeId));
            }
            if (uniqueReachableLinkIds.isEmpty()) {
                // There is no unique links covered by this zone, this zone is redundant
                redundantCentroids.add(centroidNodeId);
            }
        }
        return redundantCentroids;
    }

    private void generateZones() {
        log.info("Assigning links to the closest centroid");
        assignLinksToNearestZone();

        // after the zone is generated, update the location of the centroids (move to a better location)
        // this will lead to an updated zonal system --> we may need to run multiple iterations
        for (int i = 0; i < zoneGenerationIterations; i++) {
            int it = i + 1;
            log.info("Improving zones now. Iteration #" + it + " out of " + zoneGenerationIterations);

            List<Id<Node>> updatedCentroids = new ArrayList<>();
            for (Id<Node> originalZoneCentroidNodeId : zonalSystemData.keySet()) {
                Node currentBestCentroidNode = network.getNodes().get(originalZoneCentroidNodeId);
                List<Link> linksInZone = zonalSystemData.get(originalZoneCentroidNodeId);
                Set<Node> potentialCentroidNodes = new HashSet<>();
                linksInZone.forEach(link -> potentialCentroidNodes.add(link.getToNode()));

                double bestScore = Double.POSITIVE_INFINITY;
                for (Node potentialNewCentroidNode : potentialCentroidNodes) {
                    double cost = 0;
                    for (Link link : linksInZone) {
                        if (!linksTobeCovered.contains(link.getId())) {
                            // if this link is not relevant -> zero cost
                            continue;
                        }
                        if (!reachableLinksMap.get(potentialNewCentroidNode.getId()).contains(link.getId())) {
                            // if some link in the original zone is not reachable from here, this node cannot be a centroid
                            cost = Double.POSITIVE_INFINITY;
                            break;
                        }

                        double baseCost = calculateVrpNodeToLinkTravelTime(potentialNewCentroidNode, link);
                        if (departuresMap.isEmpty()) {
                            cost += baseCost;
                        } else {
                            int departures = departuresMap.getOrDefault(link.getId(), new MutableInt()).intValue();
                            cost += (1 + departures) * baseCost;
                        }
                    }

                    if (cost < bestScore) {
                        bestScore = cost;
                        currentBestCentroidNode = potentialNewCentroidNode;
                    }
                }
                updatedCentroids.add(currentBestCentroidNode.getId());
            }

            // re-generate the zone based on updated centroids
            zonalSystemData.clear();
            updatedCentroids.forEach(zoneId -> zonalSystemData.put(zoneId, new ArrayList<>()));
            removeRedundantCentroid();
            assignLinksToNearestZone();
        }
    }

    private void assignLinksToNearestZone() {
        log.info("Assigning links into nearest zones (i.e., nearest centroid)");
        for (Link linkBeingAssigned : network.getLinks().values()) {
            // Find the closest centroid and assign the link to that zone
            double minDistance = Double.POSITIVE_INFINITY;
            Id<Node> closestCentralNodeId = zonalSystemData.keySet().iterator().next();
            // Assign to a random centroid as initialization
            for (Id<Node> centroidNodeId : zonalSystemData.keySet()) {
                Node centroidNode = network.getNodes().get(centroidNodeId);
                double distance = calculateVrpNodeToLinkTravelTime(centroidNode, linkBeingAssigned);
                if (distance < minDistance) {
                    minDistance = distance;
                    closestCentralNodeId = centroidNodeId;
                }
            }
            zonalSystemData.get(closestCentralNodeId).add(linkBeingAssigned);
        }
    }

    private void writeOutputNetworkWithZones() {
        // Identify the neighbours for each zone, such that we can color the neighboring zones in different colors
        Map<String, Set<String>> zoneNeighborsMap = new HashMap<>();
        zonalSystemData.keySet().forEach(nodeId -> zoneNeighborsMap.put(nodeId.toString(), new HashSet<>()));
        List<String> centroids = new ArrayList<>(zoneNeighborsMap.keySet());
        int numZones = centroids.size();
        for (int i = 0; i < numZones; i++) {
            String zoneI = centroids.get(i);
            for (int j = i + 1; j < numZones; j++) {
                String zoneJ = centroids.get(j);

                Set<Node> nodesInZoneI = new HashSet<>();
                zonalSystemData.get(Id.createNodeId(zoneI)).forEach(link -> nodesInZoneI.add(link.getFromNode()));
                zonalSystemData.get(Id.createNodeId(zoneI)).forEach(link -> nodesInZoneI.add(link.getToNode()));

                Set<Node> nodesInZoneJ = new HashSet<>();
                zonalSystemData.get(Id.createNodeId(zoneJ)).forEach(link -> nodesInZoneJ.add(link.getFromNode()));
                zonalSystemData.get(Id.createNodeId(zoneJ)).forEach(link -> nodesInZoneJ.add(link.getToNode()));

                if (!Collections.disjoint(nodesInZoneI, nodesInZoneJ)) {
                    // If two zones shared any node, then we know they are neighbors
                    zoneNeighborsMap.get(zoneI).add(zoneJ);
                    zoneNeighborsMap.get(zoneJ).add(zoneI);
                }
            }
        }

        // Add attribute to the link for visualisation
        log.info("Marking links in each zone");
        // Determine the color of each zone (for visualisation)
        Map<String, Integer> coloringMap = new HashMap<>();
        zoneNeighborsMap.keySet().forEach(zoneId -> coloringMap.put(zoneId, 0));
        for (String zoneId : zoneNeighborsMap.keySet()) {
            Set<Integer> usedColor = new HashSet<>();
            for (String neighboringZone : zoneNeighborsMap.get(zoneId)) {
                usedColor.add(coloringMap.get(neighboringZone));
            }
            boolean colorFound = false;
            int i = 1;
            while (!colorFound) {
                if (usedColor.contains(i)) {
                    i++;
                } else {
                    colorFound = true;
                }
            }
            coloringMap.put(zoneId, i);
        }

        // Marking the color idx of each link
        for (Id<Node> centroidNodeId : zonalSystemData.keySet()) {
            int color = coloringMap.get(centroidNodeId.toString());
            for (Link link : zonalSystemData.get(centroidNodeId)) {
                link.getAttributes().putAttribute("zone_color", color);
                link.getAttributes().putAttribute("zone_id", centroidNodeId.toString());
            }
        }

        // Marking the relevant links (i.e. links to be covered)
        for (Id<Link> linkId : network.getLinks().keySet()) {
            if (linksTobeCovered.contains(linkId)) {
                network.getLinks().get(linkId).getAttributes().putAttribute("relevant", "yes");
            } else {
                network.getLinks().get(linkId).getAttributes().putAttribute("relevant", "no");
            }
        }

        // Marking centroid nodes
        for (Node node : network.getNodes().values()) {
            if (zonalSystemData.containsKey(node.getId())) {
                node.getAttributes().putAttribute("isCentroid", "yes");
                node.getAttributes().putAttribute("zone_color", coloringMap.get(node.getId().toString()));
            } else {
                node.getAttributes().putAttribute("isCentroid", "no");
                node.getAttributes().putAttribute("zone_color", Double.NaN);
            }
        }

        new NetworkWriter(network).write(outputNetworkWithZonesPath);
    }

    public DrtZonalSystem exportDrtZonalSystems() {
        List<DrtZone> drtZones = new ArrayList<>();
        for (Id<Node> centroidNodeId : zonalSystemData.keySet()) {
            drtZones.add(DrtZone.createDummyZone(centroidNodeId.toString(), zonalSystemData.get(centroidNodeId),
                    network.getNodes().get(centroidNodeId).getCoord()));
        }
        return new DrtZonalSystem(drtZones);
    }

    private double calculateVrpNodeToLinkTravelTime(Node fromNode, Link toLink) {
        if (freeSpeedTravelTimeSparseMatrix.get(fromNode, toLink.getFromNode()) == -1) {
            return Double.POSITIVE_INFINITY;
        }
        return freeSpeedTravelTimeSparseMatrix.get(fromNode, toLink.getFromNode()) +
                Math.ceil(toLink.getLength() / toLink.getFreespeed()) + 2;
    }

}
