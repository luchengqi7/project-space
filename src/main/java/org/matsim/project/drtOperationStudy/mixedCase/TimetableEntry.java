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
    private double slackTime;

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
        this.slackTime = departureTime - (stopDuration + arrivalTime);
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
        this.slackTime = timetableEntry.slackTime;
    }

    @Deprecated
    double delayTheStop(double delay) {
        double effectiveDelay = getEffectiveDelayIfStopIsDelayedBy(delay);
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

    double getEffectiveDelayIfStopIsDelayedBy(double delay) {
        double departureTimeAfterAddingDelay = Math.max(arrivalTime + delay, getEarliestDepartureTime()) + stopDuration;
        return departureTimeAfterAddingDelay - departureTime;
    }

    void delayTheStopBy(double delay) {
        // Note: delay can be negative (i.e., bring forward)
        arrivalTime += delay;
        departureTime = Math.max(arrivalTime, getEarliestDepartureTime()) + stopDuration;
        slackTime = departureTime - (arrivalTime + stopDuration);
    }

    void updateArrivalTime(double newArrivalTime) {
        arrivalTime = newArrivalTime;
        departureTime = Math.max(arrivalTime, getEarliestDepartureTime()) + stopDuration;
        slackTime = departureTime - (arrivalTime + stopDuration);
    }

    // Checking functions
    boolean isTimeConstraintViolated(double delay) {
        return arrivalTime + delay > getLatestArrivalTime();
    }

    @Deprecated
    double checkDelayFeasibilityAndReturnEffectiveDelay(double delay) {
        double effectiveDelay = getEffectiveDelayIfStopIsDelayedBy(delay);
        if (isTimeConstraintViolated(delay)) {
            return -1; // if not feasible, then return -1 //TODO do not use this anymore, as delay can now be negative also!!!
        }
        return effectiveDelay;
    }

    boolean isVehicleFullBeforeThisStop() {
        return occupancyBeforeStop >= capacity;
    }

    boolean isVehicleOverloaded() {
        return stopType == StopType.PICKUP ? occupancyBeforeStop >= capacity : occupancyBeforeStop > capacity;
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

    double getEarliestDepartureTime() {
        return request.earliestStartTime();
    }

    double getSlackTime() {
        return slackTime;
    }

    double getLatestArrivalTime() {
        return stopType == StopType.PICKUP ? request.latestStartTime() : request.latestArrivalTime();
    }

    // Private functions

}
