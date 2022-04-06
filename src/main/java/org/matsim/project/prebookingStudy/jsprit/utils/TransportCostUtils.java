package org.matsim.project.prebookingStudy.jsprit.utils;

public class TransportCostUtils {

    /*
     * The following statistics are extracted from https://depositonce.tu-berlin.de/handle/11303/13263, Page 21, Table 5: Today’s DRT scenarios: Estimation of the daily operator’s profit (the numbers are upscaled to the 100% population)
     */
    //Vehicle Costs
    private final static double VEHICLE_FIX_COST_PER_DAY = 17.88;//unit: €/(veh·day)
    private final static double DRIVE_COST_RATE = 17.64/3600;//unit: €/s
    private final static double VEHICLE_COSTS = VEHICLE_FIX_COST_PER_DAY + DRIVE_COST_RATE * 3600 * 8;
    public static double getVehicleCosts() {
        return VEHICLE_COSTS;
    }
    public static double getDriveCostRate() {
        return DRIVE_COST_RATE;
    }


    /*
     * The following statistics are extracted from https://www.autokostencheck.de/VW/sonstige/8-sitzer-typ-22_22750/verbrauch/
     */
    //Driven Distance Costs
    private final static double VEHICLE_OPERATING_COST_RATE = 0.18/1000;//unit: €/m
    private final static double DRIVEN_DISTANCE_COSTS = VEHICLE_OPERATING_COST_RATE;
    public static double getDrivenDistanceCosts() {
        return DRIVEN_DISTANCE_COSTS;
    }


    /*
     * The following statistics are extracted from config.xml
     */
    //Travel Distance Costs
    //ToDo: need to feed the value
    private final static double TRAVEL_DISTANCE_COSTS = Double.NaN;
    public static double getTravelDistanceCosts() {
        return TRAVEL_DISTANCE_COSTS;
    }


    /*
     * The following statistics are extracted from https://www.vtpi.org/tca/
     */
    //In-vehicle Time Costs
    //extracted from https://www.vtpi.org/tca/tca0502.pdf, Page 5.2-16, Table 5.2.7-4 Illustrative Values of Time, Passenger Transport: Road - Commuting / Private
    private final static double IN_VEHICLE_TIME_COST = 6.00/3600;//unit: €/s
    public static double getInVehicleTimeCost() {
        return IN_VEHICLE_TIME_COST;
    }

    //Dollar to Euro conversion
    private final static double DOLLAR_TO_EURO_CONVERSION = 0.9;//unit: €/$

    //Travel Time Costs
    //ToDo: need to feed the value
    //private final static double TRAVEL_TIME_COSTS = Double.NaN;
    private final static double TRAVEL_TIME_COSTS = IN_VEHICLE_TIME_COST;
    public static double getTravelTimeCosts() {
        return TRAVEL_TIME_COSTS;
    }

    //Waiting Time Costs
    //ToDo: need to feed the value
    private final static double WAITING_TIME_COSTS = Double.NaN;
    public static double getWaitingTimeCosts() {
        return WAITING_TIME_COSTS;
    }

    //ToDo: need to feed the value
    //Standard Deviation Costs
    //private final static double STANDARD_ACTIVITY_DEVIATION_COSTS = Double.NaN;
    private final static double STANDARD_ACTIVITY_DEVIATION_COSTS = IN_VEHICLE_TIME_COST;
    public static double getStandardActivityDeviationCosts() {
        return STANDARD_ACTIVITY_DEVIATION_COSTS;
    }

}
