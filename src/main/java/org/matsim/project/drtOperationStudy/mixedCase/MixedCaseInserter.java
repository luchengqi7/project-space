package org.matsim.project.drtOperationStudy.mixedCase;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.util.Coordinate;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.project.drtOperationStudy.rollingHorizon.RollingHorizonDrtOptimizer;
import org.matsim.project.drtSchoolTransportStudy.jsprit.MatrixBasedVrpCosts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MixedCaseInserter {
    private final Network network;
    private final double stopDuration;
    private final MatrixBasedVrpCosts vrpCosts;
    private final Map<Id<Link>, Location> locationByLinkId;

    public MixedCaseInserter(Network network, MatrixBasedVrpCosts vrpCosts, Map<Id<Link>, Location> locationByLinkId, DrtConfigGroup drtConfigGroup) {
        this.network = network;
        this.vrpCosts = vrpCosts;
        this.locationByLinkId = locationByLinkId;
        this.stopDuration = drtConfigGroup.stopDuration;
    }

    public void insert(DrtRequest request, Map<DvrpVehicle, List<TimetableEntry>> timetables,
                       Map<Id<DvrpVehicle>, RollingHorizonDrtOptimizer.OnlineVehicleInfo> realTimeVehicleInfoMap) {
        // Request information
        Link fromLink = request.getFromLink();
        Link toLink = request.getToLink();
        double latestPickUpTime = request.getLatestStartTime();
        double latestArrivalTime = request.getLatestArrivalTime();
        RollingHorizonDrtOptimizer.PreplannedRequest preplannedRequest = RollingHorizonDrtOptimizer.createFromRequest(request); //TODO the naming is not optimal. This is actually spontaneous request.
        RollingHorizonDrtOptimizer.PreplannedStop pickupStop = new RollingHorizonDrtOptimizer.PreplannedStop(preplannedRequest, true);
        RollingHorizonDrtOptimizer.PreplannedStop dropOffStop = new RollingHorizonDrtOptimizer.PreplannedStop(preplannedRequest, false);

        // Try to find the best insertion
        double bestInsertionCost = Double.MAX_VALUE;
        DvrpVehicle selectedVehicle = null;
        List<TimetableEntry> updatedTimetable = null;

        for (DvrpVehicle vehicle : timetables.keySet()) {
            RollingHorizonDrtOptimizer.OnlineVehicleInfo vehicleInfo = realTimeVehicleInfoMap.get(vehicle.getId());
            Link currentLink = vehicleInfo.currentLink();
            double divertableTime = vehicleInfo.divertableTime();

            List<TimetableEntry> originalTimetable = timetables.get(vehicle);

            // 1 If original timetable is empty
            if (originalTimetable.isEmpty()) {
                double timeToPickup = vrpCosts.getTransportTime(collectLocationIfAbsent(currentLink), collectLocationIfAbsent(fromLink), divertableTime, null, null);
                double arrivalTimePickUp = divertableTime + timeToPickup;
                double tripTravelTime = vrpCosts.getTransportTime(collectLocationIfAbsent(fromLink), collectLocationIfAbsent(toLink), arrivalTimePickUp + stopDuration, null, null);
                double arrivalTimeDropOff = arrivalTimePickUp + stopDuration + tripTravelTime;
                if (arrivalTimePickUp > latestPickUpTime) {
                    continue;
                }

                if (timeToPickup < bestInsertionCost) {
                    bestInsertionCost = timeToPickup;
                    selectedVehicle = vehicle;
                    updatedTimetable = new ArrayList<>();
                    updatedTimetable.add(new TimetableEntry(pickupStop, arrivalTimePickUp, arrivalTimePickUp + stopDuration, 0, stopDuration, vehicle));
                    updatedTimetable.add(new TimetableEntry(dropOffStop, arrivalTimeDropOff, vehicle.getServiceEndTime(), 1, stopDuration, vehicle));
                }
                continue;
            }

            // 2 If the timetable is not empty
            // 2.1 We first try to insert pickup. A simple algorithm is used: the best pickup location that does not violate any constraint is chosen
            double pickupInsertionCost = Double.MAX_VALUE;
            int pickupIdx = -1;
            TimetableEntry pickupStopToInsert = null;

            // 2.1.1 Insert pickup before first stop
            {
                TimetableEntry stopToInsertBefore = originalTimetable.get(0);
                Link linkOfStopToInsertBefore = network.getLinks().get(stopToInsertBefore.getPreplannedStop().getLinkId());
                double detourA = vrpCosts.getTransportTime(collectLocationIfAbsent(currentLink), collectLocationIfAbsent(fromLink), divertableTime, null, null);
                double pickupTime = divertableTime + detourA;
                if (pickupTime > latestPickUpTime) {
                    break; // Vehicle is too far away. No need to continue with this vehicle
                }
                double detourB = vrpCosts.getTransportTime(collectLocationIfAbsent(fromLink), collectLocationIfAbsent(linkOfStopToInsertBefore), pickupTime + stopDuration, null, null);
                double delay = detourA + detourB - (originalTimetable.get(0).getArrivalTime() - divertableTime);

                boolean feasible = isInsertionFeasible(originalTimetable, 0, delay);
                if (feasible && delay < pickupInsertionCost) {
                    pickupInsertionCost = delay;
                    pickupIdx = 0;
                    pickupStopToInsert = new TimetableEntry(pickupStop, pickupTime, pickupTime + stopDuration, stopToInsertBefore.getOccupancyBeforeStop(), stopDuration, vehicle);
                }
            }

            // 2.1.2 Insert pick up before each middle stop
            for (int i = 1; i < originalTimetable.size(); i++) {
                TimetableEntry stopToInsertAfter = originalTimetable.get(i - 1);
                TimetableEntry stopToInsertBefore = originalTimetable.get(i);
                Link linkOfStopToInsertAfter = network.getLinks().get(originalTimetable.get(i - 1).getPreplannedStop().getLinkId());
                Link linkOfStopToInsertBefore = network.getLinks().get(originalTimetable.get(i).getPreplannedStop().getLinkId());
                double detourA = vrpCosts.getTransportTime(collectLocationIfAbsent(linkOfStopToInsertAfter), collectLocationIfAbsent(fromLink), stopToInsertAfter.getDepartureTime(), null, null);
                double pickupTime = stopToInsertAfter.getDepartureTime() + detourA;
                if (pickupTime > latestPickUpTime) {
                    break; // Pickup not possible before the latest pickup time. No need to continue on the timetable.
                }
                double detourB = vrpCosts.getTransportTime(collectLocationIfAbsent(fromLink), collectLocationIfAbsent(linkOfStopToInsertBefore), pickupTime + stopDuration, null, null);
                double delay = detourA + detourB - (stopToInsertBefore.getArrivalTime() - stopToInsertAfter.getDepartureTime());

                boolean feasible = isInsertionFeasible(originalTimetable, i, delay);
                if (feasible && delay < pickupInsertionCost) {
                    pickupInsertionCost = delay;
                    pickupIdx = i;
                    pickupStopToInsert = new TimetableEntry(pickupStop, pickupTime, pickupTime + stopDuration, stopToInsertBefore.getOccupancyBeforeStop(), stopDuration, vehicle);
                }
            }

            {
                TimetableEntry stopToInsertAfter = originalTimetable.get(originalTimetable.size() - 1);
                Link linkOfStopToInsertAfter = network.getLinks().get(stopToInsertAfter.getPreplannedStop().getLinkId());
                double delay = vrpCosts.getTransportTime(collectLocationIfAbsent(linkOfStopToInsertAfter), collectLocationIfAbsent(fromLink), stopToInsertAfter.getDepartureTime(), null, null);
                double pickupTime = stopToInsertAfter.getDepartureTime() + delay;
                boolean feasible = pickupTime <= latestPickUpTime;
                if (feasible && delay < pickupInsertionCost) {
                    pickupInsertionCost = delay;
                    pickupIdx = originalTimetable.size();
                    pickupStopToInsert = new TimetableEntry(pickupStop, pickupTime, pickupTime + stopDuration, 0, stopDuration, vehicle);
                }
            }

            // 2.2 Insert drop off
            if (pickupIdx == -1) {
                continue; // no feasible insertion for pickup
            }

            List<TimetableEntry> temporaryTimetable = insertPickup(originalTimetable, pickupIdx, pickupStopToInsert, pickupInsertionCost); // Assume that cost = delay!!!

            int dropOffIdx = -1;
            double dropOffInsertionCost = Double.MAX_VALUE;
            TimetableEntry dropOffStopToInsert = null;

            for (int i = pickupIdx; i < temporaryTimetable.size(); i++) {
                // First, check the capacity constraint
                // Drop off is inserted after this stop. The occupancy constraint must not be violated.
                if (temporaryTimetable.get(i).checkOccupancyFeasibility()) {
                    break;
                }

                TimetableEntry stopToInsertAfter = temporaryTimetable.get(i);
                Link linkOfStopToInsertAfter = network.getLinks().get(stopToInsertAfter.getPreplannedStop().getLinkId());
                if (i + 1 < temporaryTimetable.size()) {
                    TimetableEntry stopToInsertBefore = temporaryTimetable.get(i + 1);
                    Link linkOfStopToInsertBefore = network.getLinks().get(stopToInsertBefore.getPreplannedStop().getLinkId());
                    double detourA = vrpCosts.getTransportTime(collectLocationIfAbsent(linkOfStopToInsertAfter), collectLocationIfAbsent(toLink), stopToInsertAfter.getDepartureTime(), null, null);
                    double dropOffTime = detourA + stopToInsertAfter.getDepartureTime();
                    if (dropOffTime > latestArrivalTime) {
                        break; // No more drop-off feasible after this stop. No need to continue in the timetable
                    }
                    double detourB = vrpCosts.getTransportTime(collectLocationIfAbsent(toLink), collectLocationIfAbsent(linkOfStopToInsertBefore), dropOffTime + stopDuration, null, null);
                    double delay = detourA + detourB - (stopToInsertBefore.getArrivalTime() - stopToInsertAfter.getDepartureTime());
                    boolean feasible = isInsertionFeasible(temporaryTimetable, i + 1, delay);

                    if (feasible && delay < dropOffInsertionCost) {
                        dropOffInsertionCost = delay;
                        dropOffIdx = i + 1;
                        dropOffStopToInsert = new TimetableEntry(dropOffStop, dropOffTime, dropOffTime + stopDuration, stopToInsertAfter.getOccupancyBeforeStop(), stopDuration, vehicle);
                    }

                } else {
                    // insert drop-off at the end
                    double delay = vrpCosts.getTransportTime(collectLocationIfAbsent(linkOfStopToInsertAfter), collectLocationIfAbsent(toLink), stopToInsertAfter.getDepartureTime(), null, null);
                    double dropOffTime = delay + stopToInsertAfter.getDepartureTime();
                    boolean feasible = dropOffTime <= latestArrivalTime;

                    if (feasible && delay < dropOffInsertionCost) {
                        dropOffInsertionCost = delay;
                        dropOffIdx = i + 1;
                        dropOffStopToInsert = new TimetableEntry(dropOffStop, dropOffTime, vehicle.getServiceEndTime(), stopToInsertAfter.getOccupancyBeforeStop(), stopDuration, vehicle);
                    }
                }
            }

            if (dropOffIdx != -1) {
                List<TimetableEntry> candidateTimetable = insertDropOff(temporaryTimetable, dropOffIdx, dropOffStopToInsert, dropOffInsertionCost);
                double totalCost = dropOffInsertionCost + pickupInsertionCost;
                if (totalCost < bestInsertionCost) {
                    bestInsertionCost = totalCost;
                    selectedVehicle = vehicle;
                    updatedTimetable = candidateTimetable;
                }
            }
        }

        // Insert the request to the best vehicle
        if (selectedVehicle != null) {
            timetables.put(selectedVehicle, updatedTimetable);
        }

    }

    private Location collectLocationIfAbsent(Link link) {
        return locationByLinkId.computeIfAbsent(link.getId(), linkId -> Location.Builder.newInstance()
                .setId(link.getId() + "")
                .setIndex(locationByLinkId.size())
                .setCoordinate(Coordinate.newInstance(link.getCoord().getX(), link.getCoord().getY()))
                .build());
    }

    private boolean isInsertionFeasible(List<TimetableEntry> originalTimetable, int insertionBeforeStopIdx, double delay) {
        for (int i = insertionBeforeStopIdx; i < originalTimetable.size(); i++) {
            TimetableEntry stop = originalTimetable.get(i);
            delay = stop.checkDelayFeasibility(delay); // Check delay and update the delay (because of potential "wait for stop" task)
            if (delay == 0) {
                return true; // The effective delay becomes 0, then there will be no impact on the following stops
            }

            if (delay == -1) {
                return false; // by design: delay = -1 --> insertion is not feasible
            }
        }
        return true;
    }

    private List<TimetableEntry> insertPickup(List<TimetableEntry> originalTimetable, int insertionBeforeStopIdx,
                                              TimetableEntry stopToInsert, double delay) {
        // Create a copy of the original timetable (and copy each object inside)
        List<TimetableEntry> temporaryTimetable = new ArrayList<>();
        for (TimetableEntry timetableEntry : originalTimetable) {
            temporaryTimetable.add(new TimetableEntry(timetableEntry));
        }

        if (insertionBeforeStopIdx < temporaryTimetable.size()) {
            temporaryTimetable.add(insertionBeforeStopIdx, stopToInsert);
            for (int i = insertionBeforeStopIdx + 1; i < temporaryTimetable.size(); i++) {
                delay = temporaryTimetable.get(i).delayTheStop(delay);
                temporaryTimetable.get(i).addPickupBeforeTheStop();
            }
        } else {
            temporaryTimetable.add(stopToInsert);
        }


        return temporaryTimetable;
    }

    private List<TimetableEntry> insertDropOff(List<TimetableEntry> temporaryTimetable, int insertionBeforeStopIdx,
                                               TimetableEntry stopToInsert, double delay) {
        List<TimetableEntry> candidateTimetable = new ArrayList<>();
        for (TimetableEntry timetableEntry : temporaryTimetable) {
            candidateTimetable.add(new TimetableEntry(timetableEntry));
        }

        if (insertionBeforeStopIdx < candidateTimetable.size()) {
            candidateTimetable.add(insertionBeforeStopIdx, stopToInsert);
            for (int i = insertionBeforeStopIdx + 1; i < candidateTimetable.size(); i++) {
                delay = candidateTimetable.get(i).delayTheStop(delay);
                candidateTimetable.get(i).addDropOffBeforeTheStop();
            }
        } else {
            candidateTimetable.add(stopToInsert); // insert at the end
        }


        return candidateTimetable;
    }


}
