package org.matsim.project.prebookingStudy.jsprit.utils;

public class TransportCostUtils {
    //Vehicle Costs
    private final static double FIXED_VEHICLE_OWNERSHIP_COSTS = 0;
    private final static double VARIABLE_VEHICLE_OPERATING_COSTS = 0;
    private final static double VEHICLE_COSTS = FIXED_VEHICLE_OWNERSHIP_COSTS + VARIABLE_VEHICLE_OPERATING_COSTS;
    public static double getVehicleCosts() {
        return VEHICLE_COSTS;
    }

    //Travel Distance Costs
    private final static double DRIVEN_DISTANCE_COSTS = 0;
    public static double getDrivenDistanceCosts() {
        return DRIVEN_DISTANCE_COSTS;
    }

    //Travel Distance Costs
    private final static double TRAVEL_DISTANCE_COSTS = 0;
    public static double getTravelDistanceCosts() {
        return TRAVEL_DISTANCE_COSTS;
    }

    //Travel Time Costs
    private final static double TRAVEL_TIME_COSTS = 0;
    public static double getTravelTimeCosts() {
        return TRAVEL_TIME_COSTS;
    }

    //Waiting Time Costs
    private final static double WAITING_TIME_COSTS = 0;
    public static double getWaitingTimeCosts() {
        return WAITING_TIME_COSTS;
    }

    //Standard Deviation Costs
    private final static double STANDARD_ACTIVITY_DEVIATION_COSTS = 0;
    public static double getStandardActivityDeviationCosts() {
        return STANDARD_ACTIVITY_DEVIATION_COSTS;
    }

}
