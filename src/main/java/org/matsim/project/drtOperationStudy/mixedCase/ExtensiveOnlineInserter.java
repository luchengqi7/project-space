package org.matsim.project.drtOperationStudy.mixedCase;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.zone.skims.TravelTimeMatrix;
import org.matsim.core.router.util.TravelTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.matsim.contrib.dvrp.path.VrpPaths.FIRST_LINK_TT;

class ExtensiveOnlineInserter implements OnlineInserter {
    private final Network network;
    private final double stopDuration;
    private final TravelTimeMatrix travelTimeMatrix;
    private final TravelTime travelTime;

    ExtensiveOnlineInserter(Network network, DrtConfigGroup drtConfigGroup, TravelTimeMatrix travelTimeMatrix, TravelTime travelTime) {
        this.network = network;
        this.stopDuration = drtConfigGroup.stopDuration;
        this.travelTimeMatrix = travelTimeMatrix;
        this.travelTime = travelTime;
    }

    @Override
    public Id<DvrpVehicle> insert(DrtRequest request, Map<Id<DvrpVehicle>, List<TimetableEntry>> timetables,
                                  Map<Id<DvrpVehicle>, MixedCaseDrtOptimizer.OnlineVehicleInfo> realTimeVehicleInfoMap) {
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
                double timeToPickup = calculateVrpTravelTime(currentLink, fromLink, divertableTime);
                double arrivalTimePickUp = divertableTime + timeToPickup;
                double tripTravelTime = calculateVrpTravelTime(fromLink, toLink, arrivalTimePickUp + stopDuration);
                double arrivalTimeDropOff = arrivalTimePickUp + stopDuration + tripTravelTime;
                double totalInsertionCost = timeToPickup + tripTravelTime;
                if (arrivalTimePickUp > latestPickUpTime) {
                    continue;
                }

                if (totalInsertionCost < bestInsertionCost) {
                    bestInsertionCost = totalInsertionCost;
                    selectedVehicle = vehicleInfo.vehicle();
                    updatedTimetable = new ArrayList<>();
                    updatedTimetable.add(new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.PICKUP, arrivalTimePickUp, arrivalTimePickUp + stopDuration, 0, stopDuration, selectedVehicle));
                    updatedTimetable.add(new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.DROP_OFF, arrivalTimeDropOff, arrivalTimeDropOff + stopDuration, 1, stopDuration, selectedVehicle));
                    // Note: The departure time of the last stop is actually not meaningful, but this stop may become non-last stop later, therefore, we set the departure time of this stop as if it is a middle stop
                }
                continue;
            }

            // 2 If the timetable is not empty
            // Try to insert request in the timetable, BEFORE stop i (i.e., not including appending at the end)
            boolean noNeedToContinueWithThisVehicle = false;
            for (int i = 0; i < originalTimetable.size(); i++) {
                TimetableEntry stopAfterPickUpInsertion = originalTimetable.get(i);
                if (stopAfterPickUpInsertion.isVehicleFullBeforeThisStop()) {
                    continue; // Not possible to insert pickup at this location, try next location
                }
                Link linkOfStopAfterPickUpInsertion = network.getLinks().get(stopAfterPickUpInsertion.getLinkId());

                double detourA;
                double detourB;
                double pickupTime;
                double delayCausedByPickupDetour;
                if (i == 0) {
                    detourA = calculateVrpTravelTime(currentLink, fromLink, divertableTime);
                    pickupTime = divertableTime + detourA;
                    if (pickupTime > latestPickUpTime) {
                        noNeedToContinueWithThisVehicle = true;
                        break; // Vehicle cannot reach the pickup location in time. No need to continue with this vehicle
                    }
                    detourB = calculateVrpTravelTime(fromLink, linkOfStopAfterPickUpInsertion, pickupTime + stopDuration);
                    delayCausedByPickupDetour = detourA + detourB - calculateVrpTravelTime(currentLink, linkOfStopAfterPickUpInsertion, divertableTime);
                } else {
                    TimetableEntry stopBeforePickUpInsertion = originalTimetable.get(i - 1);
                    Link linkOfStopBeforePickUpInsertion = network.getLinks().get(stopBeforePickUpInsertion.getLinkId());
                    detourA = calculateVrpTravelTime(linkOfStopBeforePickUpInsertion, fromLink, stopBeforePickUpInsertion.getDepartureTime());
                    pickupTime = stopBeforePickUpInsertion.getDepartureTime() + detourA;
                    if (pickupTime > latestPickUpTime) {
                        noNeedToContinueWithThisVehicle = true;
                        break; // Vehicle cannot reach the pickup location in time from this point. No need to continue on the timetable.
                    }
                    detourB = calculateVrpTravelTime(fromLink, linkOfStopAfterPickUpInsertion, pickupTime + stopDuration);
                    delayCausedByPickupDetour = detourA + detourB - calculateVrpTravelTime(linkOfStopBeforePickUpInsertion, linkOfStopAfterPickUpInsertion, stopBeforePickUpInsertion.getDepartureTime());
                }

                boolean isPickupFeasible = isInsertionFeasible(originalTimetable, i, delayCausedByPickupDetour + stopDuration);
                if (isPickupFeasible) {
                    TimetableEntry pickupStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.PICKUP,
                            pickupTime, pickupTime + stopDuration, stopAfterPickUpInsertion.getOccupancyBeforeStop(), stopDuration, vehicleInfo.vehicle());
                    List<TimetableEntry> temporaryTimetable = insertPickup(originalTimetable, i, pickupStopToInsert, delayCausedByPickupDetour + stopDuration);

                    // Try to insert drop off from here (insert drop off AFTER the stop j)
                    for (int j = i; j < temporaryTimetable.size(); j++) {
                        if (temporaryTimetable.get(j).isVehicleOverloaded()) {
                            break; // Drop off must be inserted before this stop. No need to continue with the timetable
                        }
                        TimetableEntry stopBeforeDropOffInsertion = temporaryTimetable.get(j);
                        Link linkOfStopBeforeDropOffInsertion = network.getLinks().get(stopBeforeDropOffInsertion.getLinkId());
                        if (j + 1 < temporaryTimetable.size()) {
                            // Append drop off between j and j+1
                            TimetableEntry stopAfterDropOffInsertion = temporaryTimetable.get(j + 1);
                            Link linkOfStopAfterDropOffInsertion = network.getLinks().get(stopAfterDropOffInsertion.getLinkId());
                            double detourC = calculateVrpTravelTime(linkOfStopBeforeDropOffInsertion, toLink, stopBeforeDropOffInsertion.getDepartureTime());
                            double dropOffTime = detourC + stopBeforeDropOffInsertion.getDepartureTime();
                            if (dropOffTime > latestArrivalTime) {
                                break; // No more drop-off feasible after this stop. No need to continue in the timetable
                            }
                            double detourD = calculateVrpTravelTime(toLink, linkOfStopAfterDropOffInsertion, dropOffTime + stopDuration);
                            double delayCausedByDropOffDetour = detourC + detourD - calculateVrpTravelTime(linkOfStopBeforeDropOffInsertion, linkOfStopAfterDropOffInsertion, stopBeforeDropOffInsertion.getDepartureTime());
                            boolean isDropOffIsFeasible = isInsertionFeasible(temporaryTimetable, j + 1, delayCausedByDropOffDetour + stopDuration);
                            double totalInsertionCost = delayCausedByDropOffDetour + delayCausedByPickupDetour; // Currently, we assume cost = total extra drive time caused by the insertion

                            if (isDropOffIsFeasible && totalInsertionCost < bestInsertionCost) {
                                TimetableEntry dropOffStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.DROP_OFF,
                                        dropOffTime, dropOffTime + stopDuration, stopAfterDropOffInsertion.getOccupancyBeforeStop(), stopDuration, vehicleInfo.vehicle());
                                updatedTimetable = insertDropOff(temporaryTimetable, j + 1, dropOffStopToInsert, delayCausedByDropOffDetour + stopDuration);
                                bestInsertionCost = totalInsertionCost;
                                selectedVehicle = vehicleInfo.vehicle();
                            }
                        } else {
                            // Append drop off at the end
                            double detourC = calculateVrpTravelTime(linkOfStopBeforeDropOffInsertion, toLink, stopBeforeDropOffInsertion.getDepartureTime());
                            double dropOffTime = detourC + stopBeforeDropOffInsertion.getDepartureTime();
                            double totalInsertionCost = detourC + delayCausedByPickupDetour;
                            boolean isDropOffFeasible = dropOffTime <= latestArrivalTime;

                            if (isDropOffFeasible && totalInsertionCost < bestInsertionCost) {
                                TimetableEntry dropOffStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.DROP_OFF,
                                        dropOffTime, dropOffTime + stopDuration, 1, stopDuration, vehicleInfo.vehicle());
                                updatedTimetable = insertDropOff(temporaryTimetable, j + 1, dropOffStopToInsert, detourC + stopDuration);
                                bestInsertionCost = totalInsertionCost;
                                selectedVehicle = vehicleInfo.vehicle();
                            }
                        }
                    }
                }
            }

            // Try to append the request at the end
            if (!noNeedToContinueWithThisVehicle) {
                TimetableEntry stopBeforePickUpInsertion = originalTimetable.get(originalTimetable.size() - 1);
                Link linkOfStopBeforePickUpInsertion = network.getLinks().get(stopBeforePickUpInsertion.getLinkId());
                double timeToPickUp = calculateVrpTravelTime(linkOfStopBeforePickUpInsertion, fromLink, stopBeforePickUpInsertion.getDepartureTime());
                double pickupTime = stopBeforePickUpInsertion.getDepartureTime() + timeToPickUp;
                if (pickupTime <= latestPickUpTime) {
                    double tripTravelTime = calculateVrpTravelTime(fromLink, toLink, pickupTime + stopDuration);
                    double dropOffTime = pickupTime + stopDuration + tripTravelTime;
                    double totalInsertionCost = timeToPickUp + tripTravelTime;
                    if (totalInsertionCost < bestInsertionCost) {
                        TimetableEntry pickupStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.PICKUP,
                                pickupTime, pickupTime + stopDuration, 0, stopDuration, vehicleInfo.vehicle());
                        TimetableEntry dropOffStopToInsert = new TimetableEntry(spontaneousRequest, TimetableEntry.StopType.DROP_OFF,
                                dropOffTime, dropOffTime + stopDuration, 1, stopDuration, vehicleInfo.vehicle());
                        List<TimetableEntry> temporaryTimetable = insertPickup(originalTimetable, originalTimetable.size(), pickupStopToInsert, timeToPickUp + stopDuration);

                        updatedTimetable = insertDropOff(temporaryTimetable, temporaryTimetable.size(), dropOffStopToInsert, tripTravelTime + stopDuration);
                        bestInsertionCost = totalInsertionCost;
                        selectedVehicle = vehicleInfo.vehicle();
                    }
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

    private boolean isInsertionFeasible(List<TimetableEntry> originalTimetable, int insertionIdx, double delay) {
        for (int i = insertionIdx; i < originalTimetable.size(); i++) {
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
        // Note: Delay includes the pickup time
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
        // Note: Delay includes the Drop-off time
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

    private double calculateVrpTravelTime(Link fromLink, Link toLink, double departureTime) {
        if (fromLink.getId().toString().equals(toLink.getId().toString())) {
            return 0;
        }
        return FIRST_LINK_TT + travelTimeMatrix.getTravelTime(fromLink.getToNode(), toLink.getFromNode(), departureTime)
                + VrpPaths.getLastLinkTT(travelTime, toLink, departureTime);
    }

}
