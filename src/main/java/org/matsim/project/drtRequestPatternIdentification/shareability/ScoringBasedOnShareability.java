package org.matsim.project.drtRequestPatternIdentification.shareability;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.drtRequestPatternIdentification.basicStructures.DrtDemand;
import org.matsim.project.drtRequestPatternIdentification.run.ExampleCode;
import org.matsim.project.utils.LinkToLinkTravelTimeMatrix;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Deprecated
public class ScoringBasedOnShareability {

    public static void main(String[] args) {
        System.out.println("This class is deprecated!!!");
    }

//    public static void main(String[] args) {
//        String configPath = "/path/to/config/file";
//        if (args.length != 0) {
//            configPath = args[0];
//        }
//
//        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
//        Scenario scenario = ScenarioUtils.loadScenario(config);
//        MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
//        DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next();
//
//        Population population = scenario.getPopulation();
//        Network network = scenario.getNetwork();
//
//        // Create router (based on free speed)
//        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
//
//        // Getting drt setups
//        double alpha = drtConfigGroup.maxTravelTimeAlpha;
//        double beta = drtConfigGroup.maxTravelTimeBeta;
//        double maxWaitTime = drtConfigGroup.maxWaitTime;
//        double stopDuration = drtConfigGroup.stopDuration;
//
//        // Go through input plans and collect all relevant links
//        MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
//        Set<Id<Link>> relevantLinks = new HashSet<>();
//        List<DrtDemand> drtTrips = new ArrayList<>();
//
//        for (Person person : population.getPersons().values()) {
//            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
//            for (TripStructureUtils.Trip trip : trips) {
//                if (mainModeIdentifier.identifyMainMode(trip.getTripElements()).equals(TransportMode.drt)) {
//                    double departureTime = trip.getOriginActivity().getEndTime().orElseThrow(RuntimeException::new);
//                    Link fromLink = network.getLinks().get(trip.getOriginActivity().getLinkId());
//                    Link toLink = network.getLinks().get(trip.getDestinationActivity().getLinkId());
//
//                    drtTrips.add(new DrtDemand(person.getId().toString(), fromLink, toLink, departureTime));
//                    relevantLinks.add(fromLink.getId());
//                    relevantLinks.add(toLink.getId());
//                }
//            }
//        }
//
//        // Generate link to link travel time matrix
//        LinkToLinkTravelTimeMatrix travelTimeMatrix = new LinkToLinkTravelTimeMatrix(network, travelTime, relevantLinks, 0);
//
//
//        // Go through each request pair, determine if sharing is feasible, when yes, calculate the potential of savings in driving time
//        int numOfTrips = drtTrips.size();
//        int counter = 0;
//        double shareablePairs = 0;
//        double savingsScore = 0;
//
//        // Initialize sharing matrix
//        double[][] drtSharingMatrix = new double[numOfTrips][numOfTrips];
//
//        for (int i = 0; i < numOfTrips; i++) {
//            for (int j = i + 1; j < numOfTrips; j++) {
//                ExampleCode.DrtTripInfo tripA = drtTrips.get(i);
//                ExampleCode.DrtTripInfo tripB = drtTrips.get(j);
//
//                double tripADirectTravelTime = travelTimeMatrix.getTravelTime(tripA.fromLink(), tripA.toLink(), tripA.departureTime());
//                double tripBDirectTravelTime = travelTimeMatrix.getTravelTime(tripB.fromLink(), tripB.toLink(), tripB.departureTime());
//
//                double tripALatestDepartureTime = tripA.departureTime() + maxWaitTime;
//                double tripBLatestDepartureTime = tripB.departureTime() + maxWaitTime;
//
//                double tripALatestArrivalTime = tripA.departureTime() + alpha * tripADirectTravelTime + beta;
//                double tripBLatestArrivalTime = tripB.departureTime() + alpha * tripBDirectTravelTime + beta;
//
//                double minTotalPoolingDriveTime = Double.MAX_VALUE;
//                boolean shareable = false;
//
//                // Case 1: o1 o2 d1 d2
//                {
//                    double now = tripA.departureTime() + stopDuration;
//                    double pooledTravelTime = 0;
//                    double o1o2 = travelTimeMatrix.getTravelTime(tripA.fromLink(), tripB.fromLink(), now);
//                    pooledTravelTime += o1o2;
//                    double arrivalTimeO2 = now + o1o2;
//                    if (arrivalTimeO2 <= tripBLatestDepartureTime) {
//                        now = Math.max(tripB.departureTime(), arrivalTimeO2) + stopDuration;
//                        double o2d1 = travelTimeMatrix.getTravelTime(tripB.fromLink(), tripA.toLink(), now);
//                        pooledTravelTime += o2d1;
//                        double arrivalTimeD1 = now + o2d1;
//                        if (arrivalTimeD1 <= tripALatestArrivalTime) {
//                            now = arrivalTimeD1 + stopDuration;
//                            double d1d2 = travelTimeMatrix.getTravelTime(tripA.toLink(), tripB.toLink(), now);
//                            pooledTravelTime += d1d2;
//                            double arrivalTimeD2 = now + d1d2;
//                            if (arrivalTimeD2 <= tripBLatestArrivalTime) {
//                                shareable = true;
//                                if (pooledTravelTime < minTotalPoolingDriveTime) {
//                                    minTotalPoolingDriveTime = pooledTravelTime;
//                                }
//                            }
//                        }
//                    }
//                }
//
//                // Case 2: o1 o2 d2 d1
//                {
//                    double now = tripA.departureTime() + stopDuration;
//                    double pooledTravelTime = 0;
//                    double o1o2 = travelTimeMatrix.getTravelTime(tripA.fromLink(), tripB.fromLink(), now);
//                    pooledTravelTime += o1o2;
//                    double arrivalTimeO2 = now + o1o2;
//                    if (arrivalTimeO2 <= tripBLatestDepartureTime) {
//                        now = Math.max(tripB.departureTime(), arrivalTimeO2) + stopDuration;
//                        double o2d2 = travelTimeMatrix.getTravelTime(tripB.fromLink(), tripB.toLink(), now);
//                        double arrivalTimeD2 = now + o2d2;
//                        pooledTravelTime += o2d2;
//                        if (arrivalTimeD2 <= tripBLatestArrivalTime) {
//                            now = arrivalTimeD2 + stopDuration;
//                            double d2d1 = travelTimeMatrix.getTravelTime(tripB.toLink(), tripA.toLink(), now);
//                            pooledTravelTime += d2d1;
//                            double arrivalTimeD1 = now + d2d1;
//                            if (arrivalTimeD1 <= tripALatestArrivalTime) {
//                                shareable = true;
//                                if (pooledTravelTime < minTotalPoolingDriveTime) {
//                                    minTotalPoolingDriveTime = pooledTravelTime;
//                                }
//                            }
//                        }
//                    }
//                }
//
//                // Case 3: o2 o1 d1 d2
//                {
//                    double now = tripB.departureTime() + stopDuration;
//                    double pooledTravelTime = 0;
//                    double o2o1 = travelTimeMatrix.getTravelTime(tripB.fromLink(), tripA.fromLink(), now);
//                    pooledTravelTime += o2o1;
//                    double arrivalTimeO1 = now + o2o1;
//                    if (arrivalTimeO1 <= tripALatestDepartureTime) {
//                        now = Math.max(tripA.departureTime(), arrivalTimeO1) + stopDuration;
//                        double o1d1 = travelTimeMatrix.getTravelTime(tripA.fromLink(), tripA.toLink(), now);
//                        double arrivalTimeD1 = now + o1d1;
//                        pooledTravelTime += o1d1;
//                        if (arrivalTimeD1 <= tripALatestArrivalTime) {
//                            now = arrivalTimeD1 + stopDuration;
//                            double d1d2 = travelTimeMatrix.getTravelTime(tripA.toLink(), tripB.toLink(), now);
//                            double arrivalTimeD2 = now + d1d2;
//                            pooledTravelTime += d1d2;
//                            if (arrivalTimeD2 <= tripBLatestArrivalTime) {
//                                shareable = true;
//                                if (pooledTravelTime < minTotalPoolingDriveTime) {
//                                    minTotalPoolingDriveTime = pooledTravelTime;
//                                }
//                            }
//                        }
//                    }
//                }
//
//
//                // Case 4:  o2 o1 d2 d1
//                {
//                    double now = tripB.departureTime() + stopDuration;
//                    double pooledTravelTime = 0;
//                    double o2o1 = travelTimeMatrix.getTravelTime(tripB.fromLink(), tripA.fromLink(), now);
//                    pooledTravelTime += o2o1;
//                    double arrivalTimeO1 = now + o2o1;
//                    if (arrivalTimeO1 <= tripALatestDepartureTime) {
//                        now = Math.max(tripA.departureTime(), arrivalTimeO1) + stopDuration;
//                        double o1d2 = travelTimeMatrix.getTravelTime(tripA.fromLink(), tripB.toLink(), now);
//                        double arrivalTimeD2 = now + o1d2;
//                        pooledTravelTime += o1d2;
//                        if (arrivalTimeD2 <= tripBLatestArrivalTime) {
//                            now = arrivalTimeD2 + stopDuration;
//                            double d2d1 = travelTimeMatrix.getTravelTime(tripB.toLink(), tripA.toLink(), now);
//                            double arrivalTimeD1 = now + d2d1;
//                            pooledTravelTime += d2d1;
//                            if (arrivalTimeD1 <= tripALatestArrivalTime) {
//                                shareable = true;
//                                if (pooledTravelTime < minTotalPoolingDriveTime) {
//                                    minTotalPoolingDriveTime = pooledTravelTime;
//                                }
//                            }
//                        }
//                    }
//                }
//
//                if (shareable) {
//                    shareablePairs++;
//                    double unsharedSumDriveTime = tripADirectTravelTime + tripBDirectTravelTime;
//                    double savingPotential = unsharedSumDriveTime / minTotalPoolingDriveTime;
//                    //TODO consider a more complex criteria
//                    savingsScore += savingPotential;
//                    drtSharingMatrix[i][j] = savingPotential;
//                }
//                counter++;
//            }
//        }
//
//        double shareabilityScore = shareablePairs / counter;
//        double driveTimeSavingsScore = savingsScore / counter;
//
//        System.out.println("Counter = " + counter);
//        System.out.println("Shareable pairs = " + shareablePairs);
//        System.out.println("Savings score = " + savingsScore);
//
//        System.out.println("Shareability score = " + shareabilityScore);
//        System.out.println("Drive time savings score = " + driveTimeSavingsScore);
//
//        System.out.println("Calculating number of pairs based on sharing potential...");
//        int numOfSharedTrips = 0;
//        int iIndex = -1;
//        int jIndex = -1;
//
//        while (true) {
//            double maxPotential = -1;
//            for (int i = 0; i < numOfTrips; i++) {
//                for (int j = 0; j < numOfTrips; j++) {
//                    double sharingPotential = drtSharingMatrix[i][j];
//                    if (sharingPotential > maxPotential) {
//                        maxPotential = sharingPotential;
//                        iIndex = i;
//                        jIndex = j;
//                    }
//                }
//            }
//
//            if (maxPotential > 0) {
//                numOfSharedTrips += 2;
//            } else {
//                break;
//            }
//
//            for (int i = 0; i < numOfTrips; i++) {
//                drtSharingMatrix[i][iIndex] = 0;
//                drtSharingMatrix[i][jIndex] = 0;
//            }
//
//            for (int j = 0; j < numOfTrips; j++) {
//                drtSharingMatrix[iIndex][j] = 0;
//                drtSharingMatrix[jIndex][j] = 0;
//            }
//        }
//
//        double sharingRate = (double) numOfSharedTrips / numOfTrips;
//        System.out.println("Number of shared trips = " + numOfSharedTrips);
//        System.out.println("Shareability = " + sharingRate);
//    }

}
