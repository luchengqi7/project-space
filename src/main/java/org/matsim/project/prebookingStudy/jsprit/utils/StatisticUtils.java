package org.matsim.project.prebookingStudy.jsprit.utils;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.EuclideanDistanceCalculator;

import java.util.*;

//regarding printing csv
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Time;

public class StatisticUtils {

    final Config config;
    final String separator;
    final boolean enableNetworkBasedCosts;
    VehicleRoutingTransportCosts transportCosts;
    final double serviceTimeInMatsim;

    final Map<String,Shipment> shipments = new HashMap<>();
    final Map<String,Job> unAssignedShipments = new HashMap<>();
    final Map<String,Shipment> assignedShipments = new HashMap<>();
    final Map<String, Double> waitingTimeMap = new HashMap<>();
    final Map<String, Double> inVehicleTimeMap = new HashMap<>();
    final Map<String, Double> travelTimeMap = new HashMap<>();
    final Map<String, Double> passengerTraveledDistanceMap = new HashMap<>();
    final Map<String, Double> pickupTimeMap = new HashMap<>();
    final Map<String, Double> deliveryTimeMap = new HashMap<>();
    final Map<String, Double> directTravelTimeMap = new HashMap<>();
    final Map<String, Double> directTravelDistanceMap = new HashMap<>();

    final Map<String, Double> drivenDistanceMap = new HashMap<>();
    final Map<String, Double> occupiedDistanceMap = new HashMap<>();
    final Map<String, Double> emptyDistanceMap = new HashMap<>();
    final Map<String, Double> drivenTimeMap = new HashMap<>();

    Map<String, Double> desiredPickupTimeMap = new HashMap<>();
    Map<String, Double> desiredDeliveryTimeMap = new HashMap<>();
    public void setDesiredPickupTimeMap(Map<String, Double> desiredPickupTimeMap) {
        this.desiredPickupTimeMap = desiredPickupTimeMap;
    }
    public void setDesiredDeliveryTimeMap(Map<String, Double> desiredDeliveryTimeMap) {
        this.desiredDeliveryTimeMap = desiredDeliveryTimeMap;
    }


    public StatisticUtils(Config config, VehicleRoutingTransportCosts transportCosts, double ServiceTimeInMatsim) {
        this.config = config;
        this.separator = config.global().getDefaultDelimiter();
        this.enableNetworkBasedCosts = true;
        this.transportCosts = transportCosts;
        this.serviceTimeInMatsim = ServiceTimeInMatsim;
    }
    public StatisticUtils(Config config, double ServiceTimeInMatsim) {
        this.config = config;
        this.separator = config.global().getDefaultDelimiter();
        this.enableNetworkBasedCosts = false;
        this.serviceTimeInMatsim = ServiceTimeInMatsim;
    }


    public void writeConfig(String outputFilename){
        if (!outputFilename.endsWith("/")) outputFilename = outputFilename + "/";
        ConfigUtils.writeConfig(config, outputFilename + "output_config.xml");
    }

    public void statsCollector(VehicleRoutingProblem problem, VehicleRoutingProblemSolution solution) {
        List<VehicleRoute> list = new ArrayList<>(solution.getRoutes());
        list.sort(new com.graphhopper.jsprit.core.util.VehicleIndexComparator());
        Map<String, Job> jobs = problem.getJobs();

        for (Job j : problem.getJobs().values()) {
            if (j instanceof Shipment) {
                Shipment jShipment = (Shipment) j;
                shipments.put(jShipment.getId(),jShipment);
            }
        }
        for (Job unassignedJob : solution.getUnassignedJobs()) {
            unAssignedShipments.put(unassignedJob.getId(), unassignedJob);
        }
        for (Map.Entry<String, Shipment> shipmentEntry : shipments.entrySet()) {
            if (!unAssignedShipments.containsKey(shipmentEntry.getKey())){
                assignedShipments.put(shipmentEntry.getKey(), shipmentEntry.getValue());
            }
        }

        for (VehicleRoute route : list) {
            Map<String, Double> realPickupArrivalTimeMap = new HashMap<>();
            Map<String, Double> realPickupDepartureTimeMap = new HashMap<>();
            Map<String, Double> routeTravelDistanceMap = new HashMap<>();
            Location lastStopLocation = null;
            TourActivity prevAct = route.getStart();
            double lastStopDepartureTime = prevAct.getEndTime();
            Location vehicleLastLocation = prevAct.getLocation();
            double drivenDistance = 0.;
            double drivenTime = 0.;
            for (TourActivity act : route.getActivities()) {
                if((("pickupShipment").equals(act.getName()))|(("deliverShipment").equals(act.getName()))){
                    String jobId;
                    if (act instanceof TourActivity.JobActivity) {
                        jobId = ((TourActivity.JobActivity) act).getJob().getId();
                    } else {
                        //ToDO: use other char?
                        jobId = "-";
                    }

                    double lastLegDistance;
                    if (enableNetworkBasedCosts) {
                        if (lastStopLocation == null) {
                            lastLegDistance = 0.;
                        } else {
                            lastLegDistance = transportCosts.getDistance(lastStopLocation, act.getLocation(), lastStopDepartureTime, null);
                        }
                        drivenDistance += transportCosts.getDistance(vehicleLastLocation, act.getLocation(), lastStopDepartureTime, null);
                        drivenTime += transportCosts.getTransportCost(vehicleLastLocation, act.getLocation(), lastStopDepartureTime, null, null);
                    } else {
                        if (lastStopLocation == null) {
                            lastLegDistance = 0.;
                        } else {
                            lastLegDistance = EuclideanDistanceCalculator.calculateDistance(lastStopLocation.getCoordinate(), act.getLocation().getCoordinate());
                        }
                        drivenDistance += EuclideanDistanceCalculator.calculateDistance(vehicleLastLocation.getCoordinate(), act.getLocation().getCoordinate());
                        drivenTime += EuclideanDistanceCalculator.calculateDistance(vehicleLastLocation.getCoordinate(), act.getLocation().getCoordinate())/route.getVehicle().getType().getMaxVelocity();
                    }
                    if (("pickupShipment").equals(act.getName())) {
                        //time-related:
                        double pickupTime = act.getArrTime();
                        pickupTimeMap.put(jobId, pickupTime);

                        /*
                         * calculatedRealWaitingTime does not include the serviceTime of pickup and delivery.
                         */
                        double calculatedRealWaitingTime = act.getArrTime() - shipments.get(jobId).getPickupTimeWindow().getStart();
                        double realWaitingTime = calculatedRealWaitingTime < 0 ? 0 : calculatedRealWaitingTime;
                        waitingTimeMap.put(jobId, realWaitingTime);
                        //realPickupArrivalTimeMap.put(jobId, act.getArrTime());
                        realPickupDepartureTimeMap.put(jobId, act.getEndTime());


                        //distance-related:
                        if (routeTravelDistanceMap.containsKey(jobId)) {
                            throw new RuntimeException("routeTravelDistanceMap.containsKey(jobId)");
                        } else {
                            routeTravelDistanceMap.replaceAll((s, v) -> v + lastLegDistance);
                            routeTravelDistanceMap.put(jobId, 0.);
                        }
                        lastStopLocation = act.getLocation();
                        lastStopDepartureTime = act.getEndTime();
                        vehicleLastLocation = act.getLocation();
                    } else if (("deliverShipment").equals(act.getName())) {
                        //time-related:
                        double deliveryTime = act.getArrTime();
                        deliveryTimeMap.put(jobId, deliveryTime);

                        /*
                         * realInVehicleTime does not include the serviceTime of pickup and delivery.
                         */
                        double realInVehicleTime = act.getArrTime() - realPickupDepartureTimeMap.get(jobId);
                        inVehicleTimeMap.put(jobId, realInVehicleTime);


                        /*
                         * realTravelTime includes only the service time of pickup which is consistent with MATSim.
                         */
                        double realTravelTime = waitingTimeMap.get(jobId) + serviceTimeInMatsim + realInVehicleTime;
                        travelTimeMap.put(jobId, realTravelTime);


                        //distance-related:
                        if (routeTravelDistanceMap.containsKey(jobId)) {
                            routeTravelDistanceMap.replaceAll((s, v) -> v + lastLegDistance);
                            passengerTraveledDistanceMap.put(jobId, routeTravelDistanceMap.get(jobId));
                            routeTravelDistanceMap.remove(jobId);
                        } else {
                            throw new RuntimeException("!routeTravelDistanceMap.containsKey(jobId)");
                        }
                        lastStopLocation = act.getLocation();
                        lastStopDepartureTime = act.getEndTime();
                        vehicleLastLocation = act.getLocation();
                    }
                }
            }
            TourActivity afterAct = route.getEnd();
            drivenDistanceMap.put(route.getVehicle().getId(), drivenDistance);
            drivenTimeMap.put(route.getVehicle().getId(), drivenTime);
        }

        if (enableNetworkBasedCosts) {
            for (Shipment shipment : assignedShipments.values()) {
                double directTravelDistance = transportCosts.getDistance(shipment.getPickupLocation(), shipment.getDeliveryLocation(), pickupTimeMap.get(shipment.getId()), null);
                directTravelDistanceMap.put(shipment.getId(), directTravelDistance);
                double directTravelTime = transportCosts.getTransportTime(shipment.getPickupLocation(), shipment.getDeliveryLocation(), pickupTimeMap.get(shipment.getId()), null, null);
                directTravelTimeMap.put(shipment.getId(), directTravelTime);
            }
        }
    }

    public void writeOutputTrips(String outputFilename) {

        List<String> strList = new ArrayList<String>() {{
            add("person");
            add("request_id");
            add("pickup_time");
            add("deliver_time");
            add("in-veh_time");
            add("trav_time");
            add("wait_time");
            add("trav_distance");
            add("euclidean_distance");
            add("start_link");
            add("start_x");
            add("start_y");
            add("end_link");
            add("end_x");
            add("end_y");

            if (enableNetworkBasedCosts) {
                add("direct_trav_time");
                add("direct_trav_distance");
            }
        }};
        String[] tripsHeader = strList.toArray(new String[strList.size()]);

        //ToDo: do not use BufferedWriter
        if (!outputFilename.endsWith("/")) outputFilename = outputFilename + "/";
        try (CSVPrinter tripsCsvPrinter = new CSVPrinter(IOUtils.getBufferedWriter(outputFilename + "output_trips.csv"),
                CSVFormat.DEFAULT.withDelimiter(separator.charAt(0)).withHeader(tripsHeader))

        ) {

            //ToDo: check the order of assignedShipments <- .values()
            for (Shipment shipment : assignedShipments.values()) {
                List<List<String>> tripRecords = new ArrayList<>();
                for (int i = 0; i < 1; i++) {
                    List<String> tripRecord = new ArrayList<>();
                    tripRecords.add(tripRecord);


                    String shipmentId = shipment.getId();

                    //ToDo: @Chengqi, how to avoid this?
                    String personId = null;
                    for (String retval : shipment.getId().split("#", 2)) {
                        personId = retval;
                        break;
                    }

                    Coordinate pickupLocation = shipment.getPickupLocation().getCoordinate();
                    Coordinate deliveryLocation = shipment.getDeliveryLocation().getCoordinate();
                    double euclideanDistance = EuclideanDistanceCalculator.calculateDistance(pickupLocation, deliveryLocation);

                    //add records
                    tripRecord.add(personId);
                    tripRecord.add(shipment.getId());
                    tripRecord.add(Time.writeTime(desiredPickupTimeMap.get(shipmentId) > pickupTimeMap.get(shipmentId) ? desiredPickupTimeMap.get(shipmentId) : pickupTimeMap.get(shipmentId)));
                    tripRecord.add(Time.writeTime(deliveryTimeMap.get(shipmentId)));
                    tripRecord.add(Time.writeTime(inVehicleTimeMap.get(shipmentId)));
                    tripRecord.add(Time.writeTime(travelTimeMap.get(shipmentId)));
                    tripRecord.add(Time.writeTime(waitingTimeMap.get(shipmentId)));
                    tripRecord.add(Double.toString(/*(int) Math.round(distance)*/passengerTraveledDistanceMap.get(shipmentId)));
                    tripRecord.add(Double.toString(euclideanDistance));

                    tripRecord.add(String.valueOf(shipment.getPickupLocation().getId()));
                    tripRecord.add(Double.toString(pickupLocation.getX()));
                    tripRecord.add(Double.toString(pickupLocation.getY()));
                    tripRecord.add(String.valueOf(shipment.getDeliveryLocation().getId()));
                    tripRecord.add(Double.toString(deliveryLocation.getX()));
                    tripRecord.add(Double.toString(deliveryLocation.getY()));

                    //add KPIs which are related network-based costs
                    if (enableNetworkBasedCosts) {
                        tripRecord.add(Time.writeTime(directTravelTimeMap.get(shipmentId)));
                        tripRecord.add(Double.toString(/*(int) Math.round(distance)*/directTravelDistanceMap.get(shipmentId)));
                    }

                    if (tripsHeader.length != tripRecord.size()) {
                        throw new RuntimeException("TRIPSHEADER.length != tripRecord.size()");
                    }
                }

                tripsCsvPrinter.printRecords(tripRecords);
            }
            System.out.println("shipment number: " + assignedShipments.size());
        } catch (IOException e) {

            e.printStackTrace();
        }

    }

    public void writeCustomerStats(String outputFilename) {

        List<String> strList = new ArrayList<String>() {{
            add("rides");
            add("wait_average");
            add("wait_max");
            add("wait_p95");
            add("wait_p75");
            add("wait_median");
            add("percentage_WT_below_10");
            add("percentage_WT_below_15");
            add("inVehicleTravelTime_mean");
            add("distance_m_mean");
            if (enableNetworkBasedCosts) {
                add("directDistance_m_mean");
            }
            add("totalTravelTime_mean");

            if (enableNetworkBasedCosts) {
                add("onboardDelayRatio_mean");
                add("detourDistanceRatio_mean");
            }

            //add KPIs mainly for school children
            add("assignment_rate");
            add("rejection_rate");

            add("early-arrival_average");
            add("early-arrival_max");
            add("early-arrival_p95");
            add("early-arrival_p75");
            add("early-arrival_median");
            add("percentage_early-arrival_above_15");
            add("percentage_early-arrival_above_30");
        }};

        String[] tripsHeader = strList.toArray(new String[strList.size()]);

        //ToDo: do not use BufferedWriter
        if (!outputFilename.endsWith("/")) outputFilename = outputFilename + "/";
        try (CSVPrinter tripsCsvPrinter = new CSVPrinter(IOUtils.getBufferedWriter(outputFilename + "customer_stats.csv"),
                CSVFormat.DEFAULT.withDelimiter(separator.charAt(0)).withHeader(tripsHeader))

        ) {

            DescriptiveStatistics waitStats = new DescriptiveStatistics();
            DescriptiveStatistics rideStats = new DescriptiveStatistics();
            DescriptiveStatistics distanceStats = new DescriptiveStatistics();
            DescriptiveStatistics directDistanceStats = new DescriptiveStatistics();
            DescriptiveStatistics traveltimes = new DescriptiveStatistics();

            DescriptiveStatistics onboardDelayRatioStats = new DescriptiveStatistics();
            DescriptiveStatistics detourDistanceRatioStats = new DescriptiveStatistics();

            DescriptiveStatistics timeOffsetStats = new DescriptiveStatistics();

/*                DecimalFormat format = new DecimalFormat();
            format.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
            format.setMinimumIntegerDigits(1);
            format.setMaximumFractionDigits(2);
            format.setGroupingUsed(false);*/

            //ToDo: change to steam style?
            for (Double value : waitingTimeMap.values()) {
                waitStats.addValue(value.doubleValue());
            }
            for (Map.Entry<String, Double> entry : inVehicleTimeMap.entrySet()) {
                double actualInVehicleTime = entry.getValue().doubleValue();
                rideStats.addValue(actualInVehicleTime);

                if (enableNetworkBasedCosts) {
                    double estimatedDirectInVehicleTime = directTravelTimeMap.get(entry.getKey());
                    double onboardDelayRatio = actualInVehicleTime / estimatedDirectInVehicleTime - 1;
                    onboardDelayRatioStats.addValue(onboardDelayRatio);
                }
            }
            for (Double value : passengerTraveledDistanceMap.values()) {
                distanceStats.addValue(value.doubleValue());
            }
            if (enableNetworkBasedCosts) {
                for (Map.Entry<String, Double> entry : directTravelDistanceMap.entrySet()) {
                    double estimatedDirectTravelDistance = entry.getValue().doubleValue();
                    directDistanceStats.addValue(estimatedDirectTravelDistance);

                    double actualTravelDistance = passengerTraveledDistanceMap.get(entry.getKey());
                    double detourDistanceRatio = actualTravelDistance / estimatedDirectTravelDistance - 1;
                    detourDistanceRatioStats.addValue(detourDistanceRatio);
                }
            }
            for (Double value : travelTimeMap.values()) {
                traveltimes.addValue(value.doubleValue());
            }
            for (Map.Entry<String, Double> entry : deliveryTimeMap.entrySet()) {
                double timeOffset = desiredDeliveryTimeMap.get(entry.getKey()) - entry.getValue();
                timeOffsetStats.addValue(timeOffset);
            }
            double assignmentRate = (double) assignedShipments.size()/shipments.size();
            double rejectionRate = (double) unAssignedShipments.size()/shipments.size();


            //ToDo: check the order of assignedShipments <- .values()
            //for (Shipment shipment : assignedShipments.values()) { //for iterations
            List<List<String>> tripRecords = new ArrayList<>();
            for (int i = 0; i < 1; i++) {
                List<String> tripRecord = new ArrayList<>();
                tripRecords.add(tripRecord);


                //add records
                tripRecord.add(Integer.toString(assignedShipments.size()));
                tripRecord.add(Double.toString(waitStats.getMean()));
                tripRecord.add(Double.toString(waitStats.getMax()));
                tripRecord.add(Double.toString(waitStats.getPercentile(95)));
                tripRecord.add(Double.toString(waitStats.getPercentile(75)));
                tripRecord.add(Double.toString(waitStats.getPercentile(50)));
                tripRecord.add(Double.toString(getPercentageWaitTimeBelow(600, waitStats)));
                tripRecord.add(Double.toString(getPercentageWaitTimeBelow(900, waitStats)));
                tripRecord.add(Double.toString(rideStats.getMean()));
                tripRecord.add(Double.toString(distanceStats.getMean()));
                if (enableNetworkBasedCosts) {
                    tripRecord.add(Double.toString(directDistanceStats.getMean()));
                }
                tripRecord.add(Double.toString(traveltimes.getMean()));

                if (enableNetworkBasedCosts) {
                    tripRecord.add(Double.toString(onboardDelayRatioStats.getMean()));
                    tripRecord.add(Double.toString(detourDistanceRatioStats.getMean()));
                }

                tripRecord.add(Double.toString(assignmentRate));
                tripRecord.add(Double.toString(rejectionRate));

                tripRecord.add(Double.toString(timeOffsetStats.getMean()));
                tripRecord.add(Double.toString(timeOffsetStats.getMax()));
                tripRecord.add(Double.toString(timeOffsetStats.getPercentile(95)));
                tripRecord.add(Double.toString(timeOffsetStats.getPercentile(75)));
                tripRecord.add(Double.toString(timeOffsetStats.getPercentile(50)));
                tripRecord.add(Double.toString(getPercentageWaitTimeAbove(900, timeOffsetStats)));
                tripRecord.add(Double.toString(getPercentageWaitTimeAbove(1800, timeOffsetStats)));


                if (tripsHeader.length != tripRecord.size()) {
                    throw new RuntimeException("TRIPSHEADER.length != tripRecord.size()");
                }
            }

            tripsCsvPrinter.printRecords(tripRecords);
            //}
        } catch (IOException e) {

            e.printStackTrace();
        }

    }
    public static double getPercentageWaitTimeBelow(int timeCriteria, DescriptiveStatistics stats) {
        double[] waitingTimes = stats.getValues();

        if (waitingTimes.length == 0) {
            return Double.NaN; // to be consistent with DescriptiveStatistics
        }

        double count = (double)Arrays.stream(waitingTimes).filter(t -> t < timeCriteria).count();
        return count * 100 / waitingTimes.length;
    }
    public static double getPercentageWaitTimeAbove(int timeCriteria, DescriptiveStatistics stats) {
        double[] waitingTimes = stats.getValues();

        if (waitingTimes.length == 0) {
            return Double.NaN; // to be consistent with DescriptiveStatistics
        }

        double count = (double)Arrays.stream(waitingTimes).filter(t -> t > timeCriteria).count();
        return count * 100 / waitingTimes.length;
    }

    public void writeVehicleStats(String outputFilename, VehicleRoutingProblem problem, VehicleRoutingProblemSolution bestSolution) {

        List<String> strList = new ArrayList<String>() {{
            add("vehicles");
            add("totalDistance");
            //add("totalEmptyDistance");
            //add("emptyRatio");
            add("totalPassengerDistanceTraveled");
            add("averageDrivenDistance");
            //add("averageEmptyDistance");
            add("averagePassengerDistanceTraveled");
            //add("d_p/d_t");

            add("usedVehicleNumber");
        }};

        String[] tripsHeader = strList.toArray(new String[strList.size()]);

        //ToDo: do not use BufferedWriter
        if (!outputFilename.endsWith("/")) outputFilename = outputFilename + "/";
        try (CSVPrinter tripsCsvPrinter = new CSVPrinter(IOUtils.getBufferedWriter(outputFilename + "vehicle_stats.csv"),
                CSVFormat.DEFAULT.withDelimiter(separator.charAt(0)).withHeader(tripsHeader))

        ) {

            DescriptiveStatistics driven = new DescriptiveStatistics();
            DescriptiveStatistics passengerTraveledDistance = new DescriptiveStatistics();
            DescriptiveStatistics occupied = new DescriptiveStatistics();
            DescriptiveStatistics empty = new DescriptiveStatistics();

/*            DecimalFormat format = new DecimalFormat();
            format.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
            format.setMinimumIntegerDigits(1);
            format.setMaximumFractionDigits(2);
            format.setGroupingUsed(false);*/

            //ToDo: change to steam style
            for (Double value : passengerTraveledDistanceMap.values()) {
                passengerTraveledDistance.addValue(value.doubleValue());
            }
            for (Double value : drivenDistanceMap.values()) {
                driven.addValue(value.doubleValue());
            }
            for (Double value : occupiedDistanceMap.values()) {
                occupied.addValue(value.doubleValue());
            }
            for (Double value : emptyDistanceMap.values()) {
                empty.addValue(value.doubleValue());
            }
            //double d_p_d_t = passengerTraveledDistance.getSum() / driven.getSum();


            //ToDo: check the order of assignedShipments <- .values()
            //for (Shipment shipment : assignedShipments.values()) { //for iterations
            List<List<String>> tripRecords = new ArrayList<>();
            for (int i = 0; i < 1; i++) {
                List<String> tripRecord = new ArrayList<>();
                tripRecords.add(tripRecord);


                //add records
                tripRecord.add(Integer.toString(problem.getVehicles().size()));
                tripRecord.add(Double.toString(driven.getSum()));
                //tripRecord.add(Double.toString(empty.getSum()));
                //tripRecord.add(Double.toString(empty.getSum() / driven.getSum()));
                tripRecord.add(Double.toString(passengerTraveledDistance.getSum()));
                tripRecord.add(Double.toString(driven.getMean()));
                //tripRecord.add(Double.toString(empty.getMean()));
                tripRecord.add(Double.toString(passengerTraveledDistance.getMean()));
                //tripRecord.add(Double.toString(d_p_d_t));

                tripRecord.add(Integer.toString(bestSolution.getRoutes().size()));


                if (tripsHeader.length != tripRecord.size()) {
                    throw new RuntimeException("TRIPSHEADER.length != tripRecord.size()");
                }
            }

            tripsCsvPrinter.printRecords(tripRecords);
            //}
        } catch (IOException e) {

            e.printStackTrace();
        }

    }

    public void writeSummaryStats(String outputFilename, VehicleRoutingProblem problem, VehicleRoutingProblemSolution bestSolution) {

        List<String> strList = new ArrayList<String>() {{
            add("fleet_size");
            add("total_requests");
            add("served_requests");
            add("punctual_arrivals");
            add("service_satisfaction_rate");

            add("actual_in_vehicle_time_mean");
            add("estimated_direct_in_vehicle_time_mean");
            add("onboard_delay_ratio_mean");
            add("actual_travel_distance_mean");
            add("estimated_direct_network_distance_mean");
            add("detour_distance_ratio_mean");
            add("fleet_total_distance");
            add("fleet_efficiency");
            add("used_vehicle_number");
        }};

        String[] tripsHeader = strList.toArray(new String[strList.size()]);

        //ToDo: do not use BufferedWriter
        if (!outputFilename.endsWith("/")) outputFilename = outputFilename + "/";
        try (CSVPrinter tripsCsvPrinter = new CSVPrinter(IOUtils.getBufferedWriter(outputFilename + "summary_stats.csv"),
                CSVFormat.DEFAULT.withDelimiter(separator.charAt(0)).withHeader(tripsHeader))

        ) {

            DescriptiveStatistics inVehicleTimes = new DescriptiveStatistics();
            DescriptiveStatistics directInVehicleTimes = new DescriptiveStatistics();
            DescriptiveStatistics onboardDelayRatioStats = new DescriptiveStatistics();
            DescriptiveStatistics detourDistanceRatioStats = new DescriptiveStatistics();
            DescriptiveStatistics distanceStats = new DescriptiveStatistics();
            DescriptiveStatistics directDistanceStats = new DescriptiveStatistics();
            DescriptiveStatistics driven = new DescriptiveStatistics();

/*            DecimalFormat format = new DecimalFormat();
            format.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
            format.setMinimumIntegerDigits(1);
            format.setMaximumFractionDigits(2);
            format.setGroupingUsed(false);*/

            //ToDo: change to steam style
            int onTimeArrivalCounter = 0;
            for (Map.Entry<String, Double> entry : deliveryTimeMap.entrySet()) {
                if(desiredDeliveryTimeMap.get(entry.getKey()) >= (entry.getValue() /*+ serviceTimeInMatsim*/))
                onTimeArrivalCounter++;
            }
            double arrivalPunctuality = (double) onTimeArrivalCounter/shipments.size();

            for (Double value : inVehicleTimeMap.values()) {
                inVehicleTimes.addValue(value.doubleValue());
            }

            for (Double value : passengerTraveledDistanceMap.values()) {
                distanceStats.addValue(value.doubleValue());
            }

            if (enableNetworkBasedCosts) {
/*                for (Double value : directTravelTimeMap.values()) {
                    directInVehicleTimes.addValue(value.doubleValue());
                }*/
                for (Map.Entry<String, Double> entry : directTravelTimeMap.entrySet()) {
                    double estimatedDirectInVehicleTime = entry.getValue().doubleValue();
                    directInVehicleTimes.addValue(estimatedDirectInVehicleTime);

                    double actualInVehicleTime = inVehicleTimeMap.get(entry.getKey());
                    double onBoardDelayRatio = actualInVehicleTime / estimatedDirectInVehicleTime - 1;
                    detourDistanceRatioStats.addValue(onBoardDelayRatio);
                }

                for (Map.Entry<String, Double> entry : directTravelDistanceMap.entrySet()) {
                    double estimatedDirectTravelDistance = entry.getValue().doubleValue();
                    directDistanceStats.addValue(estimatedDirectTravelDistance);

                    double actualTravelDistance = passengerTraveledDistanceMap.get(entry.getKey());
                    double detourDistanceRatio = actualTravelDistance / estimatedDirectTravelDistance - 1;
                    onboardDelayRatioStats.addValue(detourDistanceRatio);
                }
            }

            for (Double value : drivenDistanceMap.values()) {
                driven.addValue(value.doubleValue());
            }


            //ToDo: check the order of assignedShipments <- .values()
            //for (Shipment shipment : assignedShipments.values()) { //for iterations
            List<List<String>> tripRecords = new ArrayList<>();
            for (int i = 0; i < 1; i++) {
                List<String> tripRecord = new ArrayList<>();
                tripRecords.add(tripRecord);


                //add records
                tripRecord.add(Integer.toString(problem.getVehicles().size()));
                tripRecord.add(Integer.toString(shipments.size()));
                tripRecord.add(Integer.toString(assignedShipments.size()));
                tripRecord.add(Integer.toString(onTimeArrivalCounter));
                tripRecord.add(Double.toString(arrivalPunctuality));

                tripRecord.add(Double.toString(inVehicleTimes.getMean()));
                if (enableNetworkBasedCosts) {
                    tripRecord.add(Double.toString(directInVehicleTimes.getMean()));
                    tripRecord.add(Double.toString(onboardDelayRatioStats.getMean()));
                }
                tripRecord.add(Double.toString(distanceStats.getMean()));
                if (enableNetworkBasedCosts) {
                    tripRecord.add(Double.toString(directDistanceStats.getMean()));
                    tripRecord.add(Double.toString(detourDistanceRatioStats.getMean()));
                }
                tripRecord.add(Double.toString(driven.getSum()));
                tripRecord.add(Double.toString(directDistanceStats.getSum()/driven.getSum()));
                tripRecord.add(Integer.toString(bestSolution.getRoutes().size()));


                if (tripsHeader.length != tripRecord.size()) {
                    throw new RuntimeException("TRIPSHEADER.length != tripRecord.size()");
                }
            }

            tripsCsvPrinter.printRecords(tripRecords);
            //}
        } catch (IOException e) {

            e.printStackTrace();
        }

    }

}
