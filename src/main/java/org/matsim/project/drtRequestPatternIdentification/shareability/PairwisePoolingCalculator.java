package org.matsim.project.drtRequestPatternIdentification.shareability;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.router.util.TravelTime;
import org.matsim.project.drtRequestPatternIdentification.basicStructures.DrtDemand;
import org.matsim.project.drtRequestPatternIdentification.basicStructures.DemandsPatternCore;
import org.matsim.project.drtRequestPatternIdentification.basicStructures.Tools;
import org.matsim.project.utils.LinkToLinkTravelTimeMatrix;

import java.util.List;
import java.util.Set;

public class PairwisePoolingCalculator {
    private final Network network;
    private final double alpha;
    private final double beta;
    private final double maxWaitTime;
    private final double stopDuration;
    private final TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
    private final Logger log = LogManager.getLogger(PairwisePoolingCalculator.class);
    private LinkToLinkTravelTimeMatrix travelTimeMatrix;

    public PairwisePoolingCalculator(DrtConfigGroup drtConfigGroup, Network network, LinkToLinkTravelTimeMatrix travelTimeMatrix) {
        this.network = network;
        this.alpha = drtConfigGroup.maxTravelTimeAlpha;
        this.beta = drtConfigGroup.maxTravelTimeBeta;
        this.maxWaitTime = drtConfigGroup.maxWaitTime;
        this.stopDuration = drtConfigGroup.stopDuration;
        this.travelTimeMatrix = travelTimeMatrix;
    }

    public PairwisePoolingCalculator(DrtConfigGroup drtConfigGroup, Network network) {
        this.network = network;
        this.alpha = drtConfigGroup.maxTravelTimeAlpha;
        this.beta = drtConfigGroup.maxTravelTimeBeta;
        this.maxWaitTime = drtConfigGroup.maxWaitTime;
        this.stopDuration = drtConfigGroup.stopDuration;
        this.travelTimeMatrix = null;
    }

    public DemandsPatternCore quantifyDemands(List<DrtDemand> drtDemands) {
        // initialize travelTimeMatrix if it is null
        if (travelTimeMatrix == null) {
            Set<Id<Link>> relevantLinks = Tools.collectRelevantLink(drtDemands);
            travelTimeMatrix = new LinkToLinkTravelTimeMatrix(network, travelTime, relevantLinks, 0);
        }

        // Go through each pair of drt demand and calculate share-ability and the total trip length (time).
        int numOfTrips = drtDemands.size();

        // Initialize sharing matrix
        double[][] drtSharingMatrix = new double[numOfTrips][numOfTrips];

        // Initialize other statistics
        int numberOfPairs = 0;
        double shareablePairs = 0;
        double sumPoolingScore = 0;
        double totalTripLength = 0;

        for (int i = 0; i < drtDemands.size(); i++) {
            DrtDemand demandA = drtDemands.get(i);
            double directTravelTimeA = travelTimeMatrix.getTravelTime(demandA.fromLink(), demandA.toLink(), demandA.departureTime());
            totalTripLength += directTravelTimeA;
            for (int j = i + 1; j < drtDemands.size(); j++) {
                DrtDemand demandB = drtDemands.get(j);
                double directTravelTimeB = travelTimeMatrix.getTravelTime(demandB.fromLink(), demandB.toLink(), demandB.departureTime());

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
                    double poolingScore = unsharedSumDriveTime / minTotalPoolingDriveTime;
                    drtSharingMatrix[i][j] = poolingScore;
                    sumPoolingScore += poolingScore;
                }
                numberOfPairs++;
            }
        }

        double averageTripDirectDuration = totalTripLength / numOfTrips;
        double shareability = calculateShareability(drtSharingMatrix);

        double sharingPotential = sumPoolingScore / numberOfPairs;
        log.info("Number of shareable pairs = " + shareablePairs + " out of " + numberOfPairs +
                " pairs. Ratio = " + shareablePairs / numberOfPairs);
        log.info("Sharing potential = " + sharingPotential);
        log.info("Sum direct trip duration = " + totalTripLength);

        return new DemandsPatternCore(numOfTrips, averageTripDirectDuration, shareability);
    }

    private double calculateShareability(double[][] drtSharingMatrix) {
        log.info("Calculating shareability based on heuristic approach...");
        int numOfTrips = drtSharingMatrix[0].length;
        int numOfSharedTrips = 0;
        int iIndex = -1;
        int jIndex = -1;

        while (true) {
            double maxPotential = -1;
            for (int i = 0; i < numOfTrips; i++) {
                for (int j = 0; j < numOfTrips; j++) {
                    double sharingPotential = drtSharingMatrix[i][j];
                    if (sharingPotential > maxPotential) {
                        maxPotential = sharingPotential;
                        iIndex = i;
                        jIndex = j;
                    }
                }
            }

            if (maxPotential > 0) {
                numOfSharedTrips += 2;
            } else {
                break;
            }

            for (int i = 0; i < numOfTrips; i++) {
                drtSharingMatrix[i][iIndex] = 0;
                drtSharingMatrix[i][jIndex] = 0;
                drtSharingMatrix[iIndex][i] = 0;
                drtSharingMatrix[jIndex][i] = 0;
            }
        }

        double shareability = (double) numOfSharedTrips / numOfTrips;
        log.info("Number of shared trips is " + numOfSharedTrips + ", out of total trips of " + numOfTrips);
        log.info("Shareability = " + shareability);

        return shareability;
    }
}
