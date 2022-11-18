package org.matsim.project.drtOperationStudy.mixedCase;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

public class TimetableEntry {

    enum StopType {PICKUP, DROP_OFF}

    private final MixedCaseDrtOptimizer.GeneralRequest request;
    final StopType stopType;
    private double arrivalTime;
    private double departureTime;
    private int occupancyBeforeStop;
    private final double stopDuration;
    private final int capacity;

    public TimetableEntry(MixedCaseDrtOptimizer.GeneralRequest request, StopType stopType, double arrivalTime,
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
    public TimetableEntry(TimetableEntry timetableEntry) {
        this.request = timetableEntry.request;
        this.stopType = timetableEntry.stopType;
        this.arrivalTime = timetableEntry.arrivalTime;
        this.departureTime = timetableEntry.departureTime;
        this.occupancyBeforeStop = timetableEntry.occupancyBeforeStop;
        this.stopDuration = timetableEntry.stopDuration;
        this.capacity = timetableEntry.capacity;
    }

    public double delayTheStop(double delay) {
        double effectiveDelay = getEffectiveDelay(delay);
        if (effectiveDelay == -1) {
            throw new RuntimeException("Effective delay should not be -1 at this stage!");
        }
        arrivalTime += delay;
        departureTime += effectiveDelay;
        return effectiveDelay;
    }

    public void addPickupBeforeTheStop() {
        occupancyBeforeStop += 1;
    }

    public void addDropOffBeforeTheStop() {
        occupancyBeforeStop -= 1;
    }

    // Checking functions
    public double checkDelayFeasibility(double delay) {
        double effectiveDelay = getEffectiveDelay(delay);
        if (!checkTimeFeasibility(delay)) {
            return -1; // if not feasible, then return -1
        }
        return effectiveDelay;
    }

    public boolean isVehicleFullBeforeThisStop() {
        return occupancyBeforeStop >= capacity;
    }

    public boolean checkOccupancyFeasibility() {
        return stopType == StopType.PICKUP ? occupancyBeforeStop < capacity : occupancyBeforeStop <= capacity;
    }

    public boolean checkTimeFeasibility(double delay) {
        if (stopType == StopType.PICKUP) {
            return arrivalTime + delay <= request.latestStartTime();
        }
        return arrivalTime + delay <= request.latestArrivalTime();
    }

    // Getter functions
    public MixedCaseDrtOptimizer.GeneralRequest getRequest() {
        return request;
    }

    public double getArrivalTime() {
        return arrivalTime;
    }

    public double getDepartureTime() {
        return departureTime;
    }

    Id<Link> getLinkId() {
        if (stopType == StopType.PICKUP) {
            return request.fromLinkId();
        }
        return request.toLinkId();
    }

    public int getOccupancyBeforeStop() {
        return occupancyBeforeStop;
    }

    // Private functions
    private double getEffectiveDelay(double delay) {
        double slackTime = departureTime - arrivalTime - stopDuration;
        double effectiveDelay = delay - slackTime;
        return Math.max(0, effectiveDelay);
    }
}
