package org.matsim.project.drtRequestPatternIdentification;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.router.util.TravelTime;
import org.matsim.project.utils.LinkToLinkTravelTimeMatrix;

import java.util.*;

public class DefaultDrtDemandsQuantificationTool {
    private final Network network;
    private final double alpha;
    private final double beta;
    private final double maxWaitTime;
    private final double stopDuration;
    private final double timeBin;
    private final TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
    private final Logger log = LogManager.getLogger(DefaultDrtDemandsQuantificationTool.class);
    private LinkToLinkTravelTimeMatrix travelTimeMatrix;

    public DefaultDrtDemandsQuantificationTool(DrtConfigGroup drtConfigGroup, Network network) {
        this.network = network;
        this.alpha = drtConfigGroup.maxTravelTimeAlpha;
        this.beta = drtConfigGroup.maxTravelTimeBeta;
        this.maxWaitTime = drtConfigGroup.maxWaitTime;
        this.stopDuration = drtConfigGroup.stopDuration;
        this.timeBin = 3600;
    }

    double performQuantification(List<DrtDemand> drtDemands) {
        // collect all relevant links
        Set<Id<Link>> relevantLinks = new HashSet<>();
        for (DrtDemand demand : drtDemands) {
            relevantLinks.add(demand.fromLink().getId());
            relevantLinks.add(demand.toLink().getId());
        }
        // Construct travel time matrix for relevant links
        travelTimeMatrix = new LinkToLinkTravelTimeMatrix(network, travelTime, relevantLinks, 0);

        // Analyze score for each time bin
        drtDemands.sort(Comparator.comparingDouble(DrtDemand::departureTime));
        double overallScore = 0;
        double startTime = 0;
        while (startTime + timeBin < 86400) {
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
            double scoreForThisTimeBin = quantifyDemands(demandsWithinThisTimeBin);
            if (scoreForThisTimeBin > overallScore) {
                // Current implementation: The time bin with the highest score is determinant one
                overallScore = scoreForThisTimeBin;
            }
            log.info("Score for this time bin =  " + scoreForThisTimeBin + ". Overall score = " + overallScore);

            startTime += 0.5 * timeBin;
        }

        // Normalized based on total number of trips
        double finalScore = overallScore / drtDemands.size();
        log.info("Final score = " + finalScore);
        return finalScore;
    }

    private double quantifyDemands(List<DrtDemand> drtDemands) {
        // Go through each pair of drt demand and calculate share-ability and the total trip length (time).
        int numberOfPairs = 0;
        double shareablePairs = 0;
        double sumDriveTimeSavingRatio = 0;
        double totalTripLength = 0;

        for (int i = 0; i < drtDemands.size(); i++) {
            for (int j = i + 1; j < drtDemands.size(); j++) {
                DrtDemand demandA = drtDemands.get(i);
                DrtDemand demandB = drtDemands.get(j);

                double directTravelTimeA = travelTimeMatrix.getTravelTime(demandA.fromLink(), demandA.toLink(), demandA.departureTime());
                double directTravelTimeB = travelTimeMatrix.getTravelTime(demandB.fromLink(), demandB.toLink(), demandB.departureTime());
                totalTripLength += directTravelTimeA;

                double latestDepartureTimeA = demandA.departureTime() + maxWaitTime;
                double latestDepartureTimeB = demandB.departureTime() + maxWaitTime;

                double latestArrivalTimeA = demandA.departureTime() + alpha * directTravelTimeA + beta;
                double latestArrivalTimeB = demandB.departureTime() + alpha * directTravelTimeB + beta;

                double minTotalPoolingDriveTime = Double.MAX_VALUE;
                boolean shareable = false;

                // Case 1: o1 o2 d1 d2
                {
                    double now = demandA.departureTime() + stopDuration;
                    double pooledTravelTime = 0;
                    double o1o2 = travelTimeMatrix.getTravelTime(demandA.fromLink(), demandB.fromLink(), now);
                    pooledTravelTime += o1o2;
                    double arrivalTimeO2 = now + o1o2;
                    if (arrivalTimeO2 <= latestDepartureTimeB) {
                        now = Math.max(demandB.departureTime(), arrivalTimeO2) + stopDuration;
                        double o2d1 = travelTimeMatrix.getTravelTime(demandB.fromLink(), demandA.toLink(), now);
                        pooledTravelTime += o2d1;
                        double arrivalTimeD1 = now + o2d1;
                        if (arrivalTimeD1 <= latestArrivalTimeA) {
                            now = arrivalTimeD1 + stopDuration;
                            double d1d2 = travelTimeMatrix.getTravelTime(demandA.toLink(), demandB.toLink(), now);
                            pooledTravelTime += d1d2;
                            double arrivalTimeD2 = now + d1d2;
                            if (arrivalTimeD2 <= latestArrivalTimeB) {
                                shareable = true;
                                if (pooledTravelTime < minTotalPoolingDriveTime) {
                                    minTotalPoolingDriveTime = pooledTravelTime;
                                }
                            }
                        }
                    }
                }

                // Case 2: o1 o2 d2 d1
                {
                    double now = demandA.departureTime() + stopDuration;
                    double pooledTravelTime = 0;
                    double o1o2 = travelTimeMatrix.getTravelTime(demandA.fromLink(), demandB.fromLink(), now);
                    pooledTravelTime += o1o2;
                    double arrivalTimeO2 = now + o1o2;
                    if (arrivalTimeO2 <= latestDepartureTimeB) {
                        now = Math.max(demandB.departureTime(), arrivalTimeO2) + stopDuration;
                        double o2d2 = travelTimeMatrix.getTravelTime(demandB.fromLink(), demandB.toLink(), now);
                        double arrivalTimeD2 = now + o2d2;
                        pooledTravelTime += o2d2;
                        if (arrivalTimeD2 <= latestArrivalTimeB) {
                            now = arrivalTimeD2 + stopDuration;
                            double d2d1 = travelTimeMatrix.getTravelTime(demandB.toLink(), demandA.toLink(), now);
                            pooledTravelTime += d2d1;
                            double arrivalTimeD1 = now + d2d1;
                            if (arrivalTimeD1 <= latestArrivalTimeA) {
                                shareable = true;
                                if (pooledTravelTime < minTotalPoolingDriveTime) {
                                    minTotalPoolingDriveTime = pooledTravelTime;
                                }
                            }
                        }
                    }
                }

                // Case 3: o2 o1 d1 d2
                {
                    double now = demandB.departureTime() + stopDuration;
                    double pooledTravelTime = 0;
                    double o2o1 = travelTimeMatrix.getTravelTime(demandB.fromLink(), demandA.fromLink(), now);
                    pooledTravelTime += o2o1;
                    double arrivalTimeO1 = now + o2o1;
                    if (arrivalTimeO1 <= latestDepartureTimeA) {
                        now = Math.max(demandA.departureTime(), arrivalTimeO1) + stopDuration;
                        double o1d1 = travelTimeMatrix.getTravelTime(demandA.fromLink(), demandA.toLink(), now);
                        double arrivalTimeD1 = now + o1d1;
                        pooledTravelTime += o1d1;
                        if (arrivalTimeD1 <= latestArrivalTimeA) {
                            now = arrivalTimeD1 + stopDuration;
                            double d1d2 = travelTimeMatrix.getTravelTime(demandA.toLink(), demandB.toLink(), now);
                            double arrivalTimeD2 = now + d1d2;
                            pooledTravelTime += d1d2;
                            if (arrivalTimeD2 <= latestArrivalTimeB) {
                                shareable = true;
                                if (pooledTravelTime < minTotalPoolingDriveTime) {
                                    minTotalPoolingDriveTime = pooledTravelTime;
                                }
                            }
                        }
                    }
                }

                // Case 4:  o2 o1 d2 d1
                {
                    double now = demandB.departureTime() + stopDuration;
                    double pooledTravelTime = 0;
                    double o2o1 = travelTimeMatrix.getTravelTime(demandB.fromLink(), demandA.fromLink(), now);
                    pooledTravelTime += o2o1;
                    double arrivalTimeO1 = now + o2o1;
                    if (arrivalTimeO1 <= latestDepartureTimeA) {
                        now = Math.max(demandA.departureTime(), arrivalTimeO1) + stopDuration;
                        double o1d2 = travelTimeMatrix.getTravelTime(demandA.fromLink(), demandB.toLink(), now);
                        double arrivalTimeD2 = now + o1d2;
                        pooledTravelTime += o1d2;
                        if (arrivalTimeD2 <= latestArrivalTimeB) {
                            now = arrivalTimeD2 + stopDuration;
                            double d2d1 = travelTimeMatrix.getTravelTime(demandB.toLink(), demandA.toLink(), now);
                            double arrivalTimeD1 = now + d2d1;
                            pooledTravelTime += d2d1;
                            if (arrivalTimeD1 <= latestArrivalTimeA) {
                                shareable = true;
                                if (pooledTravelTime < minTotalPoolingDriveTime) {
                                    minTotalPoolingDriveTime = pooledTravelTime;
                                }
                            }
                        }
                    }
                }

                if (shareable) {
                    shareablePairs++;
                    double unsharedSumDriveTime = directTravelTimeA + directTravelTimeB;
                    sumDriveTimeSavingRatio += unsharedSumDriveTime / minTotalPoolingDriveTime;
                }
                numberOfPairs++;
            }
        }

        double sharingPotential = sumDriveTimeSavingRatio / numberOfPairs;
        log.info("Number of shareable pairs = " + shareablePairs + " out of " + numberOfPairs +
                " pairs. Ratio = " + shareablePairs / numberOfPairs);
        log.info("Sharing potential = " + sharingPotential);
        log.info("Total trip length (time) = " + totalTripLength);

        // Current: workload factor / sharing potential (exp)
        // Higher workload -> more vehicles needed -> higher value
        // Higher sharing potential -> fewer vehicles needed -> lower value
        // exp operation takes care of extreme values when sharing potential is very low (or even 0)
        // TODO maybe improve this function?
        return (totalTripLength / timeBin) / Math.exp(sharingPotential);
    }

}
