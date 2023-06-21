package org.matsim.project.drtRequestPatternIdentification.shareability;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.router.util.TravelTime;
import org.matsim.project.drtRequestPatternIdentification.basicStructures.DemandsPatternCore;
import org.matsim.project.drtRequestPatternIdentification.basicStructures.DrtDemand;
import org.matsim.project.utils.LinkToLinkTravelTimeMatrix;

import java.util.*;

public class PeakValueCalculator {
    private final DrtConfigGroup drtConfigGroup;
    private final Network network;
    private final double timeBin;
    private final TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
    private final Logger log = LogManager.getLogger(PeakValueCalculator.class);

    public PeakValueCalculator(DrtConfigGroup drtConfigGroup, Network network) {
        this.drtConfigGroup = drtConfigGroup;
        this.network = network;
        this.timeBin = 3600;
    }

    public PeakValueCalculator(DrtConfigGroup drtConfigGroup, Network network, double timeBin) {
        this.drtConfigGroup = drtConfigGroup;
        this.network = network;
        this.timeBin = timeBin;
    }

    public double calculate(List<DrtDemand> drtDemands) {
        // collect all relevant links
        Set<Id<Link>> relevantLinks = new HashSet<>();
        for (DrtDemand demand : drtDemands) {
            relevantLinks.add(demand.fromLink().getId());
            relevantLinks.add(demand.toLink().getId());
        }
        // Construct travel time matrix for relevant links
        LinkToLinkTravelTimeMatrix travelTimeMatrix = new LinkToLinkTravelTimeMatrix(network, travelTime, relevantLinks, 0);

        // Construct pair-wise calculator
        PairwisePoolingCalculator pairwisePoolingCalculator = new PairwisePoolingCalculator(drtConfigGroup, network, travelTimeMatrix);

        // Analyze score for each time bin
        drtDemands.sort(Comparator.comparingDouble(DrtDemand::departureTime));
        double peakValue = 0;
        double startTime = 0;
        while (startTime + timeBin <= 86400) {
            List<DrtDemand> demandsWithinThisTimeBin = new ArrayList<>();
            double endTime = startTime + timeBin;
            for (DrtDemand drtDemand : drtDemands) {
                if (drtDemand.departureTime() < startTime) {
                    continue;
                }
                if (drtDemand.departureTime() > endTime) {
                    break;
                }
                demandsWithinThisTimeBin.add(drtDemand);
            }

            log.info("===================================================================================");
            log.info("Analyzing for time bin " + startTime + " to " + endTime);
            DemandsPatternCore demandsPatternCoreForTimeBin = pairwisePoolingCalculator.quantifyDemands(demandsWithinThisTimeBin);

            double loadForTimeBin = (demandsPatternCoreForTimeBin.averageDirectTripDuration() * demandsPatternCoreForTimeBin.numOfTrips()
                    + drtConfigGroup.stopDuration * demandsPatternCoreForTimeBin.numOfTrips() * 2)
                    / Math.exp(demandsPatternCoreForTimeBin.shareability());
            // TODO design this function

            if (loadForTimeBin > peakValue) {
                peakValue = loadForTimeBin;
            }
            log.info("Load for this time bin =  " + loadForTimeBin + ". Current max value = " + peakValue);

            startTime += 0.5 * timeBin;
        }

        // Normalized based on total number of trips
        double finalScore = peakValue;
        log.info("Final score = " + finalScore);
        return finalScore;
    }
}
