package org.matsim.project.drtOperationStudy.mixedCase;

import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.project.drtOperationStudy.rollingHorizon.RollingHorizonDrtOptimizer;

public class TimetableEntry {
    private final RollingHorizonDrtOptimizer.PreplannedStop preplannedStop;
    private double arrivalTime;
    private double departureTime;
    private int occupancyBeforeStop;
    private final double stopDuration;
    private final int capacity;

    public TimetableEntry(RollingHorizonDrtOptimizer.PreplannedStop preplannedStop, double arrivalTime,
                          double departureTime, int occupancyBeforeStop, double stopDuration,
                          DvrpVehicle vehicle) {
        this.preplannedStop = preplannedStop;
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
        this.preplannedStop = timetableEntry.preplannedStop;
        this.arrivalTime = timetableEntry.arrivalTime;
        this.departureTime = timetableEntry.departureTime;
        this.occupancyBeforeStop = timetableEntry.occupancyBeforeStop;
        this.stopDuration = timetableEntry.stopDuration;
        this.capacity = timetableEntry.capacity;
    }

    public double delayTheStop(double delay) {
        double effectiveDelay = getEffectiveDelay(delay);
        arrivalTime += delay;
        departureTime += effectiveDelay;
        return effectiveDelay;
    }

    public boolean addPickupBeforeTheStop() {
        if (occupancyBeforeStop == capacity) {
            return false;
        }
        occupancyBeforeStop += 1;
        return true;
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

    public boolean checkIfInsertBeforeIsFeasible() {
        return occupancyBeforeStop < capacity; // At least 1 more seat for new insertion
    }

    public boolean checkOccupancyFeasibility() {
        if (preplannedStop.pickup()) {
            return occupancyBeforeStop < capacity;
        }
        return occupancyBeforeStop <= capacity;
    }

//    public boolean checkTimeFeasibility() {
//        if (preplannedStop.pickup()) {
//            return preplannedStop.preplannedRequest().latestStartTime() <= arrivalTime;
//        }
//        return preplannedStop.preplannedRequest().latestArrivalTime() <= arrivalTime;
//    }

    public boolean checkTimeFeasibility(double delay) {
        if (preplannedStop.pickup()) {
            return arrivalTime + delay <= preplannedStop.preplannedRequest().latestStartTime();
        }
        return arrivalTime + delay <= preplannedStop.preplannedRequest().latestArrivalTime();
    }

    // Getter functions
    public double getArrivalTime() {
        return arrivalTime;
    }

    public double getDepartureTime() {
        return departureTime;
    }

    public RollingHorizonDrtOptimizer.PreplannedStop getPreplannedStop() {
        return preplannedStop;
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
