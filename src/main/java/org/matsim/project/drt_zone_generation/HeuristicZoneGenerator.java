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

// TODO make an interface / abstract class to eliminate repeating code

public class HeuristicZoneGenerator {
    private final Network network;
    private final String outputNetworkWithZonesPath;
    private final double timeRadius;
    private final Map<Id<Link>, Set<Id<Link>>> reachableLinksMap = new HashMap<>();
    private final SparseMatrix freeSpeedTravelTimeSparseMatrix;
    private final Set<Id<Link>> linksTobeCovered = new HashSet<>();
    private final Map<Id<Link>, MutableInt> departuresMap = new HashMap<>();
    private final int zoneGenerationIterations;
    private final List<String> consideredTripModes;
    private final Map<Id<Link>, List<Link>> zonalSystemData = new LinkedHashMap<>();

    private static final Logger log = LogManager.getLogger(HeuristicZoneGenerator.class);



    public static void main(String[] args) {
        // TODO currently only support car only network (need to use mode-specific subnetwork in the normal MATSim simulations)
        // TODO convert to MATSim command line input style
        // Current input arguments: input network path, output network (for visualization) path,
        // optional number of zone improvement iterations, optional population path
        Network inputNetwork = NetworkUtils.readNetwork(args[0]);
        String visualisationNetworkPath = args[1];
        int zoneGenerationIterations = args[2] == null ? 0 : Integer.parseInt(args[2]);
        Population inputPlans = args.length == 4 ? PopulationUtils.readPopulation(args[3]) : null;

        HeuristicZoneGenerator heuristicZoneGenerator = new ZoneGeneratorBuilder(inputNetwork, visualisationNetworkPath)
                .setInputPlans(inputPlans)
                .setZoneIterations(zoneGenerationIterations)
                .build();
        heuristicZoneGenerator.run();
    }

    public void run(){
        analyzeNetwork();
        selectInitialCentroids();
        removeRedundantCentroid();
        generateZones();
        writeOutputNetworkWithZones();
        //exportDrtZonalSystems();
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

        public HeuristicZoneGenerator build(){
            SparseMatrix freeSpeedTravelTimeSparseMatrix = TravelTimeMatrices.calculateTravelTimeSparseMatrix(network,
                    sparseMatrixMaxDistance, 0, travelTime, travelDisutility,
                    Runtime.getRuntime().availableProcessors());
            return new HeuristicZoneGenerator(network, outputNetworkWithZonesPath, timeRadius,
                    freeSpeedTravelTimeSparseMatrix, inputPlans, zoneIterations, consideredTripModes);
        }
    }

    private HeuristicZoneGenerator(Network network, String outputNetworkWithZonesPath,
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
            // Note that, the centroid of a zone may have no departure or arrival
        }
    }


    public void analyzeNetwork() {
        // Explore reachable links
        log.info("Begin analyzing network. This may take some time...");
        int networkSize = network.getLinks().size();
        ProgressPrinter networkAnalysisCounter = new ProgressPrinter("Network analysis", networkSize);

        network.getLinks().keySet().forEach(linkId -> reachableLinksMap.put(linkId, new HashSet<>()));
        List<Link> linkList = new ArrayList<>(network.getLinks().values());
        for (int i = 0; i < networkSize; i++) {
            Link fromLink = linkList.get(i);

            // Add the itself to the reachable links set (if the link is to be covered)
            if (linksTobeCovered.contains(fromLink.getId())) {
                reachableLinksMap.get(fromLink.getId()).add(fromLink.getId());
            }

            for (int j = i + 1; j < networkSize; j++) {
                Link toLink = linkList.get(j);

                // If the links are too far away (beyond sparse matrix), skip directly
                if (freeSpeedTravelTimeSparseMatrix.get(fromLink.getToNode(), toLink.getFromNode()) == -1) {
                    continue;
                }

                // Check forward direction: from link -> to link
                double forwardTravelTime = calculateVrpLinkToLinkTravelTime(fromLink, toLink);
                if (forwardTravelTime > timeRadius) {
                    continue;
                }

                // Check backward direction: to Link -> from link
                double backwardTravelTime = calculateVrpLinkToLinkTravelTime(toLink, fromLink);
                if (backwardTravelTime > timeRadius) {
                    continue;
                }

                // If we reach here, then the fromLink and toLink are reachable to each other
                // Add them into the reachable map, if they are to be covered
                if (linksTobeCovered.contains(toLink.getId())) {
                    reachableLinksMap.get(fromLink.getId()).add(toLink.getId());
                }
                if (linksTobeCovered.contains(fromLink.getId())) {
                    reachableLinksMap.get(toLink.getId()).add(fromLink.getId());
                }
            }
            networkAnalysisCounter.countUp();
        }
    }

    public void selectInitialCentroids() {
        log.info("Begin selecting centroids. This may take some time...");
        // Copy the reachable links map，including copying the sets in the values of the map
        Map<Id<Link>, Set<Id<Link>>> newlyCoveredLinksMap = createReachableLInksMapCopy();

        // Initialize uncovered links
        Set<Id<Link>> uncoveredLinkIds = new HashSet<>(linksTobeCovered);
        while (!uncoveredLinkIds.isEmpty()) {
            // score the links
            Map<Id<Link>, Double> linksScoresMap = scoreTheLinks(newlyCoveredLinksMap);
            // choose the centroid based on score map
            Id<Link> selectedLinkId = selectBasedOnScoreMap(linksScoresMap);

            // add that link to the zonal system
            zonalSystemData.put(selectedLinkId, new ArrayList<>());

            // remove all the newly covered links from the uncoveredLinkIds
            uncoveredLinkIds.removeAll(reachableLinksMap.get(selectedLinkId));

            // update the newlyCoveredLinksMap by removing links that are already covered
            for (Id<Link> linkId : newlyCoveredLinksMap.keySet()) {
                newlyCoveredLinksMap.get(linkId).removeAll(reachableLinksMap.get(selectedLinkId));
            }
        }
        log.info("Potential centroids identified. There are in total " + zonalSystemData.size() + " potential centroid points");
    }

    private Id<Link> selectBasedOnScoreMap(Map<Id<Link>, Double> linksScoresMap) {
        // Current implementation: Simply choose the link with best score
        Id<Link> selectedLinkId = null;
        double bestScore = 0;
        for (Id<Link> linkId : linksScoresMap.keySet()) {
            if (linksScoresMap.get(linkId) > bestScore) {
                bestScore = linksScoresMap.get(linkId);
                selectedLinkId = linkId;
            }
        }
        return selectedLinkId;
    }

    private Map<Id<Link>, Double> scoreTheLinks(Map<Id<Link>, Set<Id<Link>>> newlyCoveredLinksMap) {
        // weight for number of links newly covered (main objective)
        double alpha = 10;
        // weight for avg distances to newly covered departures from this point (secondary objective)
        double beta = 1;

        Map<Id<Link>, Double> linkScoresMap = new HashMap<>();
        for (Id<Link> linkId : network.getLinks().keySet()) {
            Set<Id<Link>> newlyCoveredLinkIds = newlyCoveredLinksMap.get(linkId);
            double score = alpha * newlyCoveredLinkIds.size();
            if (!departuresMap.isEmpty()) {
                List<Double> distancesToDepartures = new ArrayList<>();
                for (Id<Link> newlyCoveredLinkId : newlyCoveredLinkIds) {
                    if (!departuresMap.containsKey(newlyCoveredLinkId)) {
                        // No departures on from this link
                        continue;
                    }
                    int numDepartures = departuresMap.get(newlyCoveredLinkId).intValue();
                    double distance = calculateVrpLinkToLinkTravelTime(network.getLinks().get(linkId), network.getLinks().get(newlyCoveredLinkId)) / timeRadius;
                    for (int i = 0; i < numDepartures; i++) {
                        distancesToDepartures.add(distance);
                    }
                }
                double avgDistanceToDepartures = distancesToDepartures.isEmpty() ? 1 : distancesToDepartures.stream().mapToDouble(d -> d).average().orElse(1);
                score += beta * (1 - avgDistanceToDepartures * avgDistanceToDepartures);
            }
            linkScoresMap.put(linkId, score);
        }
        return linkScoresMap;
    }

    private Map<Id<Link>, Set<Id<Link>>> createReachableLInksMapCopy() {
        Map<Id<Link>, Set<Id<Link>>> reachableLinksMapCopy = new HashMap<>();
        for (Id<Link> linkId : network.getLinks().keySet()) {
            reachableLinksMapCopy.put(linkId, new HashSet<>(reachableLinksMap.get(linkId)));
        }
        return reachableLinksMapCopy;
    }

    public void removeRedundantCentroid() {
        log.info("Begin removing redundant centroids");
        // Find all redundant centroids
        Set<Id<Link>> redundantCentroids = identifyRedundantCentroids();
        log.info("Initial number of redundant centroids (i.e., zones) identified = " + redundantCentroids.size());

        // Remove the redundant centroid that covers the minimum number of links
        while (!redundantCentroids.isEmpty()) {
            int minReachableLinks = Integer.MAX_VALUE;
            Id<Link> centroidToRemove = null;
            for (Id<Link> redundantCentroid : redundantCentroids) {
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

        log.info("Removal of redundant centroids complete. There are " + zonalSystemData.size() + " centroids (i.e., zones) remaining");
    }

    protected Set<Id<Link>> identifyRedundantCentroids() {
        Set<Id<Link>> redundantCentroids = new HashSet<>();
        for (Id<Link> centroidLinkId : zonalSystemData.keySet()) {
            Set<Id<Link>> uniqueReachableLinkIds = new HashSet<>(reachableLinksMap.get(centroidLinkId));
            for (Id<Link> anotherCentriodLinkId : zonalSystemData.keySet()) {
                if (centroidLinkId.toString().equals(anotherCentriodLinkId.toString())) {
                    // skip itself
                    continue;
                }
                uniqueReachableLinkIds.removeAll(reachableLinksMap.get(anotherCentriodLinkId));
            }
            if (uniqueReachableLinkIds.isEmpty()) {
                // There is no unique links covered by this zone, this zone is redundant
                redundantCentroids.add(centroidLinkId);
            }
        }
        return redundantCentroids;
    }

    private void generateZones() {
        log.info("Generating zones now.");
        // generate initial zones by assigning links to nearest centroid
        assignLinksToNearestZone();

        // after the zone is generated, update the location of the centroids (move to a better location)
        // this will lead to an updated zonal system --> we may need to run multiple iterations
        for (int i = 0; i < zoneGenerationIterations; i++) {
            int it = i + 1;
            log.info("Improving zones now. Iteration #" + it + " out of " + zoneGenerationIterations);

            List<Id<Link>> updatedCentroids = new ArrayList<>();
            for (Id<Link> originalZoneCentroidLinkId : zonalSystemData.keySet()) {
                Link currentBestCentroidLink = network.getLinks().get(originalZoneCentroidLinkId);
                if (currentBestCentroidLink == null) {
                    // dummy zone (all the irrelevant zones), skip
                    continue;
                }
                List<Link> linksInZone = zonalSystemData.get(originalZoneCentroidLinkId);
                double bestScore = Double.POSITIVE_INFINITY;

                for (Link link : linksInZone) {
                    // Initialize score with free speed travel time on that link * a factor
                    // Reason: when rebalancing is performed, the centroid links (i.e., the target) will be driven on many times
                    double score = (Math.floor(link.getLength() / link.getFreespeed()) + 1) * 10;
                    for (Link anotherLink : linksInZone) {
                        if (!linksTobeCovered.contains(anotherLink.getId())) {
                            continue;
                        }
                        if (!reachableLinksMap.get(link.getId()).contains(anotherLink.getId())) {
                            // if some link in the original zone is not reachable from here, this link cannot be a centroid
                            score = Double.POSITIVE_INFINITY;
                            break;
                        }
                        score += calculateVrpLinkToLinkTravelTime(link, anotherLink);
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        currentBestCentroidLink = link;
                    }
                }
                updatedCentroids.add(currentBestCentroidLink.getId());
            }

            // re-generate the zone based on updated centroids
            zonalSystemData.clear();
            updatedCentroids.forEach(zoneId -> zonalSystemData.put(zoneId, new ArrayList<>()));
            removeRedundantCentroid();
            assignLinksToNearestZone();
        }
    }

    public void assignLinksToNearestZone() {
        log.info("Assigning links into nearest zones (i.e., nearest centroid)");
        for (Id<Link> linkId : network.getLinks().keySet()) {
            // If the link is one of the centroid link, then assign directly
            if (zonalSystemData.containsKey(linkId)) {
                zonalSystemData.get(linkId).add(network.getLinks().get(linkId));
                continue;
            }

            // Otherwise, find the closest centroid and assign the link to that zone
            Link linkBeingAssigned = network.getLinks().get(linkId);
            double minDistance = Double.POSITIVE_INFINITY;
            Id<Link> closestCentralLinkId = zonalSystemData.keySet().iterator().next();
            // Assign to a random centroid as initialization
            for (Id<Link> centroidLinkId : zonalSystemData.keySet()) {
                Link centroidLink = network.getLinks().get(centroidLinkId);
                if (freeSpeedTravelTimeSparseMatrix.get(centroidLink.getToNode(), linkBeingAssigned.getFromNode()) != -1) {
                    double distance = calculateVrpLinkToLinkTravelTime(centroidLink, linkBeingAssigned);
                    // double distance = calculateVrpLinkToLinkTravelTime(centroidLink, linkBeingAssigned) + calculateVrpLinkToLinkTravelTime(linkBeingAssigned, centroidLink);
                    // Forward + backward : better for travel time matrix
                    // only forward: better for rebalancing zone?
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestCentralLinkId = centroidLinkId;
                    }
                }
            }
            zonalSystemData.get(closestCentralLinkId).add(linkBeingAssigned);
        }
    }


    private void writeOutputNetworkWithZones() {
        // Identify the neighbours for each zone, such that we can color the neighboring zones in different colors
        Map<String, Set<String>> zoneNeighborsMap = new HashMap<>();
        zonalSystemData.keySet().forEach(linkId -> zoneNeighborsMap.put(linkId.toString(), new HashSet<>()));
        List<String> centroids = new ArrayList<>(zoneNeighborsMap.keySet());
        int numZones = centroids.size();
        for (int i = 0; i < numZones; i++) {
            String zoneI = centroids.get(i);
            for (int j = i + 1; j < numZones; j++) {
                String zoneJ = centroids.get(j);

                Set<Node> nodesInZoneI = new HashSet<>();
                zonalSystemData.get(Id.createLinkId(zoneI)).forEach(link -> nodesInZoneI.add(link.getFromNode()));
                zonalSystemData.get(Id.createLinkId(zoneI)).forEach(link -> nodesInZoneI.add(link.getToNode()));

                Set<Node> nodesInZoneJ = new HashSet<>();
                zonalSystemData.get(Id.createLinkId(zoneJ)).forEach(link -> nodesInZoneJ.add(link.getFromNode()));
                zonalSystemData.get(Id.createLinkId(zoneJ)).forEach(link -> nodesInZoneJ.add(link.getToNode()));

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

        for (Id<Link> centroiLinkId : zonalSystemData.keySet()) {
            int color = coloringMap.get(centroiLinkId.toString());
            for (Link link : zonalSystemData.get(centroiLinkId)) {
                link.getAttributes().putAttribute("zone_color", color);
                link.getAttributes().putAttribute("zone_id", centroiLinkId.toString());
            }
        }

        // Marking centroid links and relevant links (i.e., links to be covered)
        for (Id<Link> linkId : network.getLinks().keySet()) {
            if (zonalSystemData.containsKey(linkId)) {
                network.getLinks().get(linkId).getAttributes().putAttribute("isCentroid", "yes");
            } else {
                network.getLinks().get(linkId).getAttributes().putAttribute("isCentroid", "no");
            }

            if (linksTobeCovered.contains(linkId)) {
                network.getLinks().get(linkId).getAttributes().putAttribute("relevant", "yes");
            } else {
                network.getLinks().get(linkId).getAttributes().putAttribute("relevant", "no");
            }
        }
        new NetworkWriter(network).write(outputNetworkWithZonesPath);
    }

    public DrtZonalSystem exportDrtZonalSystems() {
        List<DrtZone> drtZones = new ArrayList<>();
        for (Id<Link> centroidLinkId : zonalSystemData.keySet()) {
            drtZones.add(DrtZone.createDummyZone(centroidLinkId.toString(), zonalSystemData.get(centroidLinkId),
                    network.getLinks().get(centroidLinkId).getToNode().getCoord()));
        }
        return new DrtZonalSystem(drtZones);
    }

    private double calculateVrpLinkToLinkTravelTime(Link fromLink, Link toLink) {
        return freeSpeedTravelTimeSparseMatrix.get(fromLink.getToNode(), toLink.getFromNode()) +
                Math.ceil(toLink.getLength() / toLink.getFreespeed()) + 2;
    }

}
