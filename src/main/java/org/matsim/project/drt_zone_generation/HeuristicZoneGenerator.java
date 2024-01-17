package org.matsim.project.drt_zone_generation;

import org.apache.commons.lang.mutable.MutableInt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
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
    private final double timeRadius;

    private final Map<Id<Link>, Set<Id<Link>>> reachableLinksMap = new HashMap<>();
    private final SparseMatrix freeSpeedTravelTimeSparseMatrix;
    private final Map<Id<Link>, MutableInt> departuresMap = new HashMap<>();

    private final Map<Id<Link>, List<Link>> zonalSystemData = new LinkedHashMap<>();

    private static final Logger log = LogManager.getLogger(HeuristicZoneGenerator.class);

    private static final List<String> modes = Arrays.asList(TransportMode.drt, TransportMode.car, TransportMode.pt, TransportMode.bike, TransportMode.walk);

    public static void main(String[] args) {
        Network inputNetwork = NetworkUtils.readNetwork(args[0]);
        String visualisationNetworkPath = args[1];
        Population inputPlans = args.length == 3 ? PopulationUtils.readPopulation(args[2]) : null;

        HeuristicZoneGenerator heuristicZoneGenerator = new HeuristicZoneGenerator(inputNetwork);

        heuristicZoneGenerator.analyzeNetwork();
        heuristicZoneGenerator.processPlans(inputPlans);
        heuristicZoneGenerator.selectCentroids();
        heuristicZoneGenerator.removeRedundantCentroid();
        heuristicZoneGenerator.createZonesOnNetwork(visualisationNetworkPath);
//        heuristicZoneGenerator.generateDrtZonalSystems();
    }

    public HeuristicZoneGenerator(Network network, double timeRadius, double sparseMatrixMaxDistance) {
        this.network = network;
        this.timeRadius = timeRadius;
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        this.freeSpeedTravelTimeSparseMatrix = TravelTimeMatrices.calculateTravelTimeSparseMatrix(network, sparseMatrixMaxDistance,
                0, travelTime, travelDisutility, Runtime.getRuntime().availableProcessors());
    }

    public HeuristicZoneGenerator(Network network) {
        this.network = network;
        this.timeRadius = 300;
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        this.freeSpeedTravelTimeSparseMatrix = TravelTimeMatrices.calculateTravelTimeSparseMatrix(network, 10000,
                0, travelTime, travelDisutility, Runtime.getRuntime().availableProcessors());
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

            // Add the itself to the reachable links set
            reachableLinksMap.get(fromLink.getId()).add(fromLink.getId());

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

                // If we reach here, then the toLink is fully reachable from fromLink.
                reachableLinksMap.get(fromLink.getId()).add(toLink.getId());
                reachableLinksMap.get(toLink.getId()).add(fromLink.getId());

            }
            networkAnalysisCounter.countUp();
        }
    }

    public void processPlans(Population inputPlans) {
        network.getLinks().keySet().forEach(linkId -> departuresMap.put(linkId, new MutableInt()));
        if (inputPlans != null) {
            MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
            for (Person person : inputPlans.getPersons().values()) {
                for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                    if (modes.contains(mainModeIdentifier.identifyMainMode(trip.getTripElements()))) {
                        Id<Link> fromLinkId = trip.getOriginActivity().getLinkId();
                        if (fromLinkId == null) {
                            fromLinkId = NetworkUtils.getNearestLink(network, trip.getOriginActivity().getCoord()).getId();
                        }
                        departuresMap.get(fromLinkId).increment();
                    }
                }
            }
        }
    }

    public void selectCentroids() {
        log.info("Begin selecting centroids. This may take some time...");
        // Copy the reachable links map
        Map<Id<Link>, Set<Id<Link>>> newlyCoveredLinksMap = createReachableLInksMapCopy();

        // Initialize uncovered links
        Set<Id<Link>> uncoveredLinkIds = new HashSet<>(network.getLinks().keySet());
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
        // weight for number of links with departures newly covered (highly prioritized)
        double alpha = 100;
        // weight for avg distances to newly covered departures from this point (prioritized)
        double beta = 10;
        // weight for the number of newly covered link (default)
        double gamma = 1;
        Map<Id<Link>, Double> linkScoresMap = new HashMap<>();

        for (Id<Link> linkId : network.getLinks().keySet()) {
            int numOfLinksNewlyCovered = newlyCoveredLinksMap.get(linkId).size();

            int numNewlyCoveredLinkWithDepartures = 0;
            List<Double> distancesToDepartures = new ArrayList<>();
            for (Id<Link> newlyCoveredLinkId : newlyCoveredLinksMap.get(linkId)) {
                int numDepartures = departuresMap.get(newlyCoveredLinkId).intValue();
                if (numDepartures > 0) {
                    numNewlyCoveredLinkWithDepartures++;
                    for (int i = 0; i < numDepartures; i++) {
                        distancesToDepartures.add(calculateVrpLinkToLinkTravelTime(network.getLinks().get(linkId), network.getLinks().get(newlyCoveredLinkId)) / timeRadius);
                    }
                }
            }

            double avgDistanceToDepartures = distancesToDepartures.isEmpty() ? 1 : distancesToDepartures.stream().mapToDouble(d -> d).average().orElse(1);
            double score = alpha * numNewlyCoveredLinkWithDepartures + beta * (1 - avgDistanceToDepartures * avgDistanceToDepartures) + gamma * numOfLinksNewlyCovered;
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
                    continue;
                }
                uniqueReachableLinkIds.removeAll(reachableLinksMap.get(anotherCentriodLinkId));
            }
            if (uniqueReachableLinkIds.isEmpty()) {
                redundantCentroids.add(centroidLinkId);
            }
        }
        return redundantCentroids;
    }

    public void createZonesOnNetwork(String outputNetwork) {
        // Add links to the zone
        log.info("Assigning links into zones (based on closest centroid)");
        // Record the neighbour for coloring (visualisation)
        Map<String, Set<String>> zoneNeighborsMap = new HashMap<>();
        zonalSystemData.keySet().forEach(linkId -> zoneNeighborsMap.put(linkId.toString(), new HashSet<>()));

        for (Id<Link> linkId : network.getLinks().keySet()) {
            Set<String> relevantZones = new HashSet<>();
            double minDistance = Double.POSITIVE_INFINITY;
            Id<Link> closestCentralLinkId = null;
            for (Id<Link> centroidLinkId : zonalSystemData.keySet()) {
                // If the link is the centroid link, then assign directly and no need to continue
                if (linkId.toString().equals(centroidLinkId.toString())) {
                    closestCentralLinkId = centroidLinkId;
                    break;
                }

                if (reachableLinksMap.get(centroidLinkId).contains(linkId)) {
                    relevantZones.add(centroidLinkId.toString());
                    Link centroidLink = network.getLinks().get(centroidLinkId);
                    Link link = network.getLinks().get(linkId);
                    double distance = calculateVrpLinkToLinkTravelTime(centroidLink, link) + calculateVrpLinkToLinkTravelTime(link, centroidLink);
                    // Forward + backward : this will lead to a better zoning
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestCentralLinkId = centroidLinkId;
                    }
                }
            }
            zonalSystemData.get(closestCentralLinkId).add(network.getLinks().get(linkId));
            if (relevantZones.size() > 1) {
                // if a link is relevant to (i.e., reachable from) more than one zone, then those zones are neighbours
                for (String zoneId : relevantZones) {
                    zoneNeighborsMap.get(zoneId).addAll(relevantZones);
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

        // Marking centroid links
        for (Id<Link> linkId : network.getLinks().keySet()) {
            if (zonalSystemData.containsKey(linkId)) {
                network.getLinks().get(linkId).getAttributes().putAttribute("isCentroid", "yes");
            } else {
                network.getLinks().get(linkId).getAttributes().putAttribute("isCentroid", "no");
            }
        }
        new NetworkWriter(network).write(outputNetwork);
    }

    public DrtZonalSystem generateDrtZonalSystems() {
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
