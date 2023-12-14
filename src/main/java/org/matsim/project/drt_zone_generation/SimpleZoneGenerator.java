package org.matsim.project.drt_zone_generation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.contrib.drt.analysis.zonal.DrtZonalSystem;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.contrib.zone.skims.SparseMatrix;
import org.matsim.contrib.zone.skims.TravelTimeMatrices;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import scala.Int;

import java.util.*;

/**
 * Generate zonal system for DRT network (i.e., only car mode network). All the links in a zone can be reached within a certain
 * time threshold from the centroid point.
 */
public class SimpleZoneGenerator {
    // TODO 1. Include # of departures on the link when ranking (use input plans)
    // TODO 2. Add some disturbance in the ranking and generate zones multiple times and get the best one
    // TODO 3. Speed up (e.g. multi-threading?)
    private final Network network;
    private final double timeRadius;

    private final Map<Id<Link>, Set<Id<Link>>> reachableLinksMap = new HashMap<>();
    private final Map<Id<Link>, Integer> numReachableLinksMap = new HashMap<>();
    private final SparseMatrix freeSpeedTravelTimeSparseMatrix;

    private final Map<Id<Link>, List<Link>> zonalSystemData = new LinkedHashMap<>();

    private static final Logger log = LogManager.getLogger(SimpleZoneGenerator.class);

    public SimpleZoneGenerator(double timeRadius, Network network, double sparseMatrixMaxDistance) {
        this.timeRadius = timeRadius;
        this.network = network;
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        this.freeSpeedTravelTimeSparseMatrix = TravelTimeMatrices.calculateTravelTimeSparseMatrix(network, sparseMatrixMaxDistance,
                0, travelTime, travelDisutility, Runtime.getRuntime().availableProcessors());
    }

    public SimpleZoneGenerator(Network network) {
        this.network = network;
        this.timeRadius = 300;

        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        this.freeSpeedTravelTimeSparseMatrix = TravelTimeMatrices.calculateTravelTimeSparseMatrix(network, 10000,
                0, travelTime, travelDisutility, Runtime.getRuntime().availableProcessors());
    }

    public static void main(String[] args) {
        Network inputNetwork = NetworkUtils.readNetwork(args[0]);
        SimpleZoneGenerator zoneGenerator = new SimpleZoneGenerator(inputNetwork);
        zoneGenerator.analyzeNetwork();
        zoneGenerator.calculateZonesOnNetwork(args[1]);
    }


    public void analyzeNetwork() {
        int numLinks = network.getLinks().size();
        int counter = 0;
        int pct = 0;
        int tenPct = numLinks / 10;

        // Explore reachable links and forbidden link
        log.info("Begin analyzing network " + pct + "% completed");
        network.getLinks().keySet().forEach(linkId -> reachableLinksMap.put(linkId, new HashSet<>()));
        for (Link fromLink : network.getLinks().values()) {
            for (Link toLink : network.getLinks().values()) {
                // The same link, add directly
                if (fromLink.getId().toString().equals(toLink.getId().toString())) {
                    reachableLinksMap.get(fromLink.getId()).add(toLink.getId());
                }

                // If this pair has been checked before, skip directly
                if (reachableLinksMap.get(fromLink.getId()).contains(toLink.getId())) {
                    continue;
                }

                // If the links are too far away (beyond sparse matrix), skip directly
                if (freeSpeedTravelTimeSparseMatrix.get(fromLink.getToNode(), toLink.getFromNode()) == -1) {
                    continue;
                }

                // Check forward direction: from link -> to link
                double forwardTravelTime = freeSpeedTravelTimeSparseMatrix.get(fromLink.getToNode(), toLink.getFromNode()) +
                        Math.ceil(toLink.getLength() / toLink.getFreespeed()) + 2;
                // (Based on VRP path logic)
                if (forwardTravelTime > timeRadius) {
                    continue;
                }

                // Check backward direction: to Link -> from link
                double backwardTravelTime = freeSpeedTravelTimeSparseMatrix.get(toLink.getToNode(), fromLink.getFromNode()) +
                        Math.ceil(fromLink.getLength() / fromLink.getFreespeed()) + 2;
                // (Based on VRP path logic)
                if (backwardTravelTime > timeRadius) {
                    continue;
                }

                // If we reach here, then the toLink is fully reachable from fromLink.
                reachableLinksMap.get(fromLink.getId()).add(toLink.getId());
                reachableLinksMap.get(toLink.getId()).add(fromLink.getId());
            }

            counter++;
            if (counter % tenPct == 0) {
                pct += 10;
                log.info("Processing..." + pct + "% completed");
            }
        }

        reachableLinksMap.forEach((linkId, ids) -> numReachableLinksMap.put(linkId, ids.size()));
    }

    public void calculateZonesOnNetwork(String outputNetwork) {
        // Selecting centroid by sorting the links based on num of reachable links from there
        log.info("Begin choosing centroid of zones");
        List<Id<Link>> rankedPotentialCentroids = new ArrayList<>();
        numReachableLinksMap.entrySet().
                stream().
                sorted(Map.Entry.comparingByValue(Collections.reverseOrder())).
                forEach(entry -> rankedPotentialCentroids.add(entry.getKey()));

        while (!rankedPotentialCentroids.isEmpty()) {
            Id<Link> chosenLinkId = rankedPotentialCentroids.get(0);
            zonalSystemData.put(chosenLinkId, new ArrayList<>());

            // Remove the links that have been covered
            rankedPotentialCentroids.removeAll(reachableLinksMap.get(chosenLinkId));
        }
        log.info("Centroids generation completed. There are " + zonalSystemData.size() + " centroids (i.e., zones)");

        // Add links to the zone
        log.info("Assigning links into zones (based on closest centroid)");
        Map<String, Set<String>> zoneNeighborsMap = new HashMap<>();
        zonalSystemData.keySet().forEach(linkId -> zoneNeighborsMap.put(linkId.toString(), new HashSet<>()));

        for (Id<Link> linkId : network.getLinks().keySet()) {
            Set<String> zones = new HashSet<>();
            double minDistance = Double.POSITIVE_INFINITY;
            Id<Link> closestCentralLinkId = null;
            for (Id<Link> centroidLinkId : zonalSystemData.keySet()) {
                if (linkId.toString().equals(centroidLinkId.toString())) {
                    closestCentralLinkId = centroidLinkId;
                    break;
                }

                if (reachableLinksMap.get(centroidLinkId).contains(linkId)) {
                    Link centroidLink = network.getLinks().get(centroidLinkId);
                    Link link = network.getLinks().get(linkId);
                    double distance = freeSpeedTravelTimeSparseMatrix.get(centroidLink.getToNode(), link.getFromNode()) +
                            Math.ceil(link.getLength() / link.getFreespeed()) + 2;
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestCentralLinkId = centroidLinkId;
                    }
                    zones.add(centroidLinkId.toString());
                }
            }
            zonalSystemData.get(closestCentralLinkId).add(network.getLinks().get(linkId));
            if (zones.size() > 1) {
                // neighboring zones are detected
                for (String zoneId : zones) {
                    zoneNeighborsMap.get(zoneId).addAll(zones);
                }
            }
        }

        // Add attribute to the link for visualisation
        log.info("Marking the links in the zones");
        // Determine the color of each zone (for visualisation)
        Map<String, Integer> coloringMap = new HashMap<>();
        network.getLinks().keySet().forEach(linkId -> coloringMap.put(linkId.toString(), 0));
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


    //TODO complete this after testing
    public DrtZonalSystem writeDrtZonalSystems() {
        return null;
    }

}
