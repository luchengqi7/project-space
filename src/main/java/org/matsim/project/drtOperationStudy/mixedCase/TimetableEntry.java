package org.matsim.project.drtOperationStudy.mixedCase;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

class TimetableEntry {

    enum StopType {PICKUP, DROP_OFF}

    private final MixedCaseDrtOptimizer.GeneralRequest request;
    final StopType stopType;
    private double arrivalTime;
    private double departureTime;
    private int occupancyBeforeStop;
    private final double stopDuration;
    private final int capacity;

    TimetableEntry(MixedCaseDrtOptimizer.GeneralRequest request, StopType stopType, double arrivalTime,
                   double departureTime, int occupancyBeforeStop, double stopDuration,
                   DvrpVehicle vehicle) {
        this.request = request;
        this.stopType = stopType;
        this.arrivalTime = arrivalTime;
        this.departureTime = departureTime;
        this.occupancyBeforeStop = occupancyBeforeStop;
        this.stopDuration = stopDuration;
        this.capacity = vehicle.getCapacity();
    }

    /**
     * Make a copy of the object
     */
    TimetableEntry(TimetableEntry timetableEntry) {
        this.request = timetableEntry.request;
        this.stopType = timetableEntry.stopType;
        this.arrivalTime = timetableEntry.arrivalTime;
        this.departureTime = timetableEntry.departureTime;
        this.occupancyBeforeStop = timetableEntry.occupancyBeforeStop;
        this.stopDuration = timetableEntry.stopDuration;
        this.capacity = timetableEntry.capacity;
    }

    double delayTheStop(double delay) {
        double effectiveDelay = getEffectiveDelay(delay);
        arrivalTime += delay;
        departureTime += effectiveDelay;
        return effectiveDelay;
    }

    void addPickupBeforeTheStop() {
        occupancyBeforeStop += 1;
    }

    void addDropOffBeforeTheStop() {
        occupancyBeforeStop -= 1;
    }

    // Checking functions
    double checkDelayFeasibility(double delay) {
        double effectiveDelay = getEffectiveDelay(delay);
        if (!checkTimeFeasibility(delay)) {
            return -1; // if not feasible, then return -1
        }
        return effectiveDelay;
    }

    boolean isVehicleFullBeforeThisStop() {
        return occupancyBeforeStop >= capacity;
    }

    boolean isVehicleOverloaded() {
        return stopType == StopType.PICKUP ? occupancyBeforeStop >= capacity : occupancyBeforeStop > capacity;
    }

    boolean checkTimeFeasibility(double delay) {
        if (stopType == StopType.PICKUP) {
            return arrivalTime + delay <= request.latestStartTime();
        }
        return arrivalTime + delay <= request.latestArrivalTime();
    }

    // Getter functions
    MixedCaseDrtOptimizer.GeneralRequest getRequest() {
        return request;
    }

    double getArrivalTime() {
        return arrivalTime;
    }

    double getDepartureTime() {
        return departureTime;
    }

    Id<Link> getLinkId() {
        if (stopType == StopType.PICKUP) {
            return request.fromLinkId();
        }
        return request.toLinkId();
    }

    int getOccupancyBeforeStop() {
        return occupancyBeforeStop;
    }

    // Private functions
    private double getEffectiveDelay(double delay) {
        double slackTime = departureTime - arrivalTime - stopDuration;
        double effectiveDelay = delay - slackTime;
        return Math.max(0, effectiveDelay);
    }
}
