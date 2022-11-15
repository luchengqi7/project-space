package org.matsim.project.drtOperationStudy.mixedCase;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SimpleOnlineInserter {
    private final Network network;
    private final double stopDuration;

    public SimpleOnlineInserter(Network network, DrtConfigGroup drtConfigGroup) {
        this.network = network;
        this.stopDuration = drtConfigGroup.stopDuration;
    }

    public Id<DvrpVehicle> insert(DrtRequest request, Map<Id<DvrpVehicle>, List<TimetableEntry>> timetables,
                                  Map<Id<DvrpVehicle>, MixedCaseDrtOptimizer.OnlineVehicleInfo> realTimeVehicleInfoMap,
                                  LinkToLinkTravelTimeMatrix travelTimeMatrix) {
        // Request information
        Link fromLink = request.getFromLink();
        Link toLink = request.getToLink();
        double latestPickUpTime = request.getLatestStartTime();
        double latestArrivalTime = request.getLatestArrivalTime();
        MixedCaseDrtOptimizer.GeneralRequest spontaneousRequest = MixedCaseDrtOptimizer.createFromDrtRequest(request);

        // Try to find the best insertion
        double bestInsertionCost = Double.MAX_VALUE;
        DvrpVehicle selectedVehicle = null;
        List<TimetableEntry> updatedTimetable = null;

        for (Id<DvrpVehicle> vehicleId : timetables.keySet()) {
            MixedCaseDrtOptimizer.OnlineVehicleInfo vehicleInfo = realTimeVehicleInfoMap.get(vehicleId);
            Link currentLink = vehicleInfo.currentLink();
            double divertableTime = vehicleInfo.divertableTime();

            List<TimetableEntry> originalTimetable = timetables.get(vehicleId);

            // 1 If original timetable is empty
            if (originalTimetable.isEmpty()) {
                double timeToPickup = travelTimeMatrix.getLinkToLinkTravelTime(currentLink.getId(), fromLink.getId());
                double arrivalTimePickUp = divertableTime + timeToPickup;
                double tripTravelTime = travelTimeMatrix.getLinkToLinkTravelTime(fromLink.getId(), toLink.getId());
                double arrivalTimeDropOff = arrivalTimePickUp + stopDuration + tripTravelTime;
                if (arrivalTimePickUp > latestPickUpTime) {
                    continue;
                }

                if (timeToPickup < bestInsertionCost) {
                    bestInsertionCost = timeToPickup;
                    selectedVehicle = vehicleInfo.vehicle();
                    updatedTimetable = new ArrayList<>();
                    updatedTimetable.add(new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.PICKUP, arrivalTimePickUp, arrivalTimePickUp + stopDuration, 0, stopDuration, selectedVehicle));
                    updatedTimetable.add(new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.DROP_OFF, arrivalTimeDropOff, selectedVehicle.getServiceEndTime(), 1, stopDuration, selectedVehicle));
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
                TimetableEntry stopAfterTheInsertion = originalTimetable.get(0);
                Link linkOfStopAfterTheInsertion = network.getLinks().get(stopAfterTheInsertion.getLinkId());
                double detourA = travelTimeMatrix.getLinkToLinkTravelTime(currentLink.getId(), fromLink.getId());
                double pickupTime = divertableTime + detourA;
                if (pickupTime > latestPickUpTime) {
                    break; // Vehicle is too far away. No need to continue with this vehicle
                }
                double detourB = travelTimeMatrix.getLinkToLinkTravelTime(fromLink.getId(), linkOfStopAfterTheInsertion.getId());
                double delay = detourA + detourB - (originalTimetable.get(0).getArrivalTime() - divertableTime);

                boolean feasible = isInsertionFeasible(originalTimetable, 0, delay);
                if (feasible && delay < pickupInsertionCost) {
                    pickupInsertionCost = delay;
                    pickupIdx = 0;
                    pickupStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.PICKUP,
                            pickupTime, pickupTime + stopDuration, stopAfterTheInsertion.getOccupancyBeforeStop(), stopDuration, vehicleInfo.vehicle());
                }
            }

            // 2.1.2 Insert pick up before each middle stop
            for (int i = 1; i < originalTimetable.size(); i++) {
                TimetableEntry stopBeforeTheInsertion = originalTimetable.get(i - 1);
                TimetableEntry stopAfterTheInsertion = originalTimetable.get(i);
                Link linkOfStopBeforeTheInsertion = network.getLinks().get(originalTimetable.get(i - 1).getLinkId());
                Link linkOfStopAfterTheInsertion = network.getLinks().get(originalTimetable.get(i).getLinkId());
                double detourA = travelTimeMatrix.getLinkToLinkTravelTime(linkOfStopBeforeTheInsertion.getId(), fromLink.getId());
                double pickupTime = stopBeforeTheInsertion.getDepartureTime() + detourA;
                if (pickupTime > latestPickUpTime) {
                    break; // Pickup not possible before the latest pickup time. No need to continue on the timetable.
                }
                double detourB = travelTimeMatrix.getLinkToLinkTravelTime(fromLink.getId(), linkOfStopAfterTheInsertion.getId());
                double delay = detourA + detourB - (stopAfterTheInsertion.getArrivalTime() - stopBeforeTheInsertion.getDepartureTime());

                boolean feasible = isInsertionFeasible(originalTimetable, i, delay);
                if (feasible && delay < pickupInsertionCost) {
                    pickupInsertionCost = delay;
                    pickupIdx = i;
                    pickupStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.PICKUP,
                            pickupTime, pickupTime + stopDuration, stopAfterTheInsertion.getOccupancyBeforeStop(), stopDuration, vehicleInfo.vehicle());
                }
            }

            {
                TimetableEntry stopBeforeTheInsertion = originalTimetable.get(originalTimetable.size() - 1);
                Link linkOfStopBeforeTheInsertion = network.getLinks().get(stopBeforeTheInsertion.getLinkId());
                double delay = travelTimeMatrix.getLinkToLinkTravelTime(linkOfStopBeforeTheInsertion.getId(), fromLink.getId());
                double pickupTime = stopBeforeTheInsertion.getDepartureTime() + delay;
                boolean feasible = pickupTime <= latestPickUpTime;
                if (feasible && delay < pickupInsertionCost) {
                    pickupInsertionCost = delay;
                    pickupIdx = originalTimetable.size();
                    pickupStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.PICKUP,
                            pickupTime, pickupTime + stopDuration, 0, stopDuration, vehicleInfo.vehicle());
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
                if (!temporaryTimetable.get(i).checkOccupancyFeasibility()) {
                    break;
                }

                TimetableEntry stopBeforeTheInsertion = temporaryTimetable.get(i);
                Link linkOfStopBeforeTheInsertion = network.getLinks().get(stopBeforeTheInsertion.getLinkId());
                if (i + 1 < temporaryTimetable.size()) {
                    TimetableEntry stopAfterTheInsertion = temporaryTimetable.get(i + 1);
                    Link linkOfStopAfterTheInsertion = network.getLinks().get(stopAfterTheInsertion.getLinkId());
                    double detourA = travelTimeMatrix.getLinkToLinkTravelTime(linkOfStopBeforeTheInsertion.getId(), toLink.getId());
                    double dropOffTime = detourA + stopBeforeTheInsertion.getDepartureTime();
                    if (dropOffTime > latestArrivalTime) {
                        break; // No more drop-off feasible after this stop. No need to continue in the timetable
                    }
                    double detourB = travelTimeMatrix.getLinkToLinkTravelTime(toLink.getId(), linkOfStopAfterTheInsertion.getId());
                    double delay = detourA + detourB - (stopAfterTheInsertion.getArrivalTime() - stopBeforeTheInsertion.getDepartureTime());
                    boolean feasible = isInsertionFeasible(temporaryTimetable, i + 1, delay);

                    if (feasible && delay < dropOffInsertionCost) {
                        dropOffInsertionCost = delay;
                        dropOffIdx = i + 1;
                        dropOffStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.DROP_OFF,
                                dropOffTime, dropOffTime + stopDuration, stopAfterTheInsertion.getOccupancyBeforeStop(), stopDuration, vehicleInfo.vehicle());
                        // Attention: the occupancy of the stop after the insertion is not yet deducted. Therefore, we don't need to +1 to the occupancy here.
                    }

                } else {
                    // insert drop-off at the end
                    double delay = travelTimeMatrix.getLinkToLinkTravelTime(linkOfStopBeforeTheInsertion.getId(), toLink.getId());
                    double dropOffTime = delay + stopBeforeTheInsertion.getDepartureTime();
                    boolean feasible = dropOffTime <= latestArrivalTime;

                    if (feasible && delay < dropOffInsertionCost) {
                        dropOffInsertionCost = delay;
                        dropOffIdx = i + 1;
                        dropOffStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.DROP_OFF,
                                dropOffTime, vehicleInfo.vehicle().getServiceEndTime(), 1, stopDuration, vehicleInfo.vehicle());
                    }
                }
            }

            if (dropOffIdx != -1) {
                List<TimetableEntry> candidateTimetable = insertDropOff(temporaryTimetable, dropOffIdx, dropOffStopToInsert, dropOffInsertionCost);
                double totalCost = dropOffInsertionCost + pickupInsertionCost;
                if (totalCost < bestInsertionCost) {
                    bestInsertionCost = totalCost;
                    selectedVehicle = vehicleInfo.vehicle();
                    updatedTimetable = candidateTimetable;
                }
            }
        }

        // Insert the request to the best vehicle
        if (selectedVehicle != null) {
            timetables.put(selectedVehicle.getId(), updatedTimetable);
            return selectedVehicle.getId();
        }

        return null;
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

    private List<TimetableEntry> insertPickup(List<TimetableEntry> originalTimetable, int pickUpIdx,
                                              TimetableEntry stopToInsert, double delay) {
        // Create a copy of the original timetable (and copy each object inside)
        List<TimetableEntry> temporaryTimetable = new ArrayList<>();
        for (TimetableEntry timetableEntry : originalTimetable) {
            temporaryTimetable.add(new TimetableEntry(timetableEntry));
        }

        if (pickUpIdx < temporaryTimetable.size()) {
            temporaryTimetable.add(pickUpIdx, stopToInsert);
            for (int i = pickUpIdx + 1; i < temporaryTimetable.size(); i++) {
                delay = temporaryTimetable.get(i).delayTheStop(delay);
                temporaryTimetable.get(i).addPickupBeforeTheStop();
            }
        } else {
            temporaryTimetable.add(stopToInsert); // insert at the end
        }
        return temporaryTimetable;
    }

    private List<TimetableEntry> insertDropOff(List<TimetableEntry> temporaryTimetable, int dropOffIdx,
                                               TimetableEntry stopToInsert, double delay) {
        List<TimetableEntry> candidateTimetable = new ArrayList<>();
        for (TimetableEntry timetableEntry : temporaryTimetable) {
            candidateTimetable.add(new TimetableEntry(timetableEntry));
        }

        if (dropOffIdx < candidateTimetable.size()) {
            candidateTimetable.add(dropOffIdx, stopToInsert);
            for (int i = dropOffIdx + 1; i < candidateTimetable.size(); i++) {
                delay = candidateTimetable.get(i).delayTheStop(delay);
                candidateTimetable.get(i).addDropOffBeforeTheStop();
            }
        } else {
            candidateTimetable.add(stopToInsert); // insert at the end
        }

        return candidateTimetable;
    }
}
