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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

//regarding printing csv
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.log4j.Logger;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Time;

public class StatisticUtils {

    final static double PICKUP_SERVICE_TIME_IN_MATSIM = 60.;
    final static double DELIVERY_SERVICE_TIME_IN_MATSIM = 60.;

    VehicleRoutingTransportCosts transportCosts;
    final boolean enableNetworkBasedCosts;

    final static Map<String,Shipment> shipments = new HashMap<>();
    final static Map<String, Double> waitingTimeMap = new HashMap<>();
    final static Map<String, Double> inVehicleTimeMap = new HashMap<>();
    final static Map<String, Double> travelTimeMap = new HashMap<>();
    final static Map<String, Double> passengerTraveledDistanceMap = new HashMap<>();
    final static Map<String, Double> pickupTimeMap = new HashMap<>();
    final static Map<String, Double> deliveryTimeMap = new HashMap<>();
    final static Map<String, Double> directTravelTimeMap = new HashMap<>();
    final static Map<String, Double> directTravelDistanceMap = new HashMap<>();

    final static Map<String, Double> drivenDistanceMap = new HashMap<>();
    final static Map<String, Double> occupiedDistanceMap = new HashMap<>();
    final static Map<String, Double> emptyDistanceMap = new HashMap<>();

    public Map<String, Double> getTravelTimeMap() {
        return travelTimeMap;
    }
    public Map<String, Double> getPassengerTraveledDistanceMap() {
        return passengerTraveledDistanceMap;
    }

    public StatisticUtils(VehicleRoutingTransportCosts transportCosts) {
            this.transportCosts = transportCosts;
            this.enableNetworkBasedCosts = true;
    }
    public StatisticUtils() {
        this.enableNetworkBasedCosts = false;
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

        for (VehicleRoute route : list) {
            Map<String, Double> realPickupArrivalTimeMap = new HashMap<>();
            Map<String, Double> realPickupDepartureTimeMap = new HashMap<>();
            Map<String, Double> routeTravelDistanceMap = new HashMap<>();
            Location lastStopLocation = null;
            double lastStopDepartureTime = route.getStart().getEndTime();
            TourActivity prevAct = route.getStart();
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
                    } else {
                        if (lastStopLocation == null) {
                            lastLegDistance = 0.;
                        } else {
                            lastLegDistance = EuclideanDistanceCalculator.calculateDistance(lastStopLocation.getCoordinate(), act.getLocation().getCoordinate());
                        }
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
                        double realTravelTime = waitingTimeMap.get(jobId) + PICKUP_SERVICE_TIME_IN_MATSIM + realInVehicleTime;
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
                    }
                }
            }
            TourActivity afterAct = route.getEnd();
        }

        if (enableNetworkBasedCosts) {
            for (Shipment shipment : shipments.values()) {
                double directTravelDistance = transportCosts.getDistance(shipment.getPickupLocation(), shipment.getDeliveryLocation(), pickupTimeMap.get(shipment.getId()), null);
                directTravelDistanceMap.put(shipment.getId(), directTravelDistance);
                double directTravelTime = transportCosts.getTransportTime(shipment.getPickupLocation(), shipment.getDeliveryLocation(), pickupTimeMap.get(shipment.getId()), null, null);
                directTravelTimeMap.put(shipment.getId(), directTravelTime);
            }
        }
    }

    public void writeOutputTrips(String matsimConfig, String outputFilename) {

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
        //ToDo: load config in Runner?
        String separator = ConfigUtils.loadConfig(matsimConfig).global().getDefaultDelimiter();

        //ToDo: do not use BufferedWriter
        if (!outputFilename.endsWith("/")) outputFilename = outputFilename + "/";
        try (CSVPrinter tripsCsvPrinter = new CSVPrinter(IOUtils.getBufferedWriter(outputFilename + "output_trips.csv"),
                CSVFormat.DEFAULT.withDelimiter(separator.charAt(0)).withHeader(tripsHeader))

        ) {

            //ToDo: check the order of shipments <- .values()
            for (Shipment shipment : shipments.values()) {
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
                    tripRecord.add(Time.writeTime(pickupTimeMap.get(shipmentId)));
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
            System.out.println("shipment number: " + shipments.size());
        } catch (IOException e) {

            e.printStackTrace();
        }

    }

    public void writeCustomerStats(String matsimConfig, String outputFilename) {

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
        }};

        String[] tripsHeader = strList.toArray(new String[strList.size()]);
        //ToDo: load config in Runner?
        String separator = ConfigUtils.loadConfig(matsimConfig).global().getDefaultDelimiter();

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

/*                DecimalFormat format = new DecimalFormat();
            format.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
            format.setMinimumIntegerDigits(1);
            format.setMaximumFractionDigits(2);
            format.setGroupingUsed(false);*/

            //ToDo: change to steam style
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


            //ToDo: check the order of shipments <- .values()
            //for (Shipment shipment : shipments.values()) { //for iterations
            List<List<String>> tripRecords = new ArrayList<>();
            for (int i = 0; i < 1; i++) {
                List<String> tripRecord = new ArrayList<>();
                tripRecords.add(tripRecord);


                //add records
                tripRecord.add(Integer.toString(shipments.size()));
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

    public void writeVehicleStats(String matsimConfig, String outputFilename, VehicleRoutingProblem problem) {

        List<String> strList = new ArrayList<String>() {{
            add("vehicles");
            //add("totalDistance");
            //add("totalEmptyDistance");
            //add("emptyRatio");
            add("totalPassengerDistanceTraveled");
            //add("averageDrivenDistance");
            //add("averageEmptyDistance");
            add("averagePassengerDistanceTraveled");
            //add("d_p/d_t");
        }};

        String[] tripsHeader = strList.toArray(new String[strList.size()]);
        //ToDo: load config in Runner?
        String separator = ConfigUtils.loadConfig(matsimConfig).global().getDefaultDelimiter();

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


            //ToDo: check the order of shipments <- .values()
            //for (Shipment shipment : shipments.values()) { //for iterations
            List<List<String>> tripRecords = new ArrayList<>();
            for (int i = 0; i < 1; i++) {
                List<String> tripRecord = new ArrayList<>();
                tripRecords.add(tripRecord);


                //add records
                tripRecord.add(Integer.toString(problem.getVehicles().size()));
                //tripRecord.add(Double.toString(driven.getSum()));
                //tripRecord.add(Double.toString(empty.getSum()));
                //tripRecord.add(Double.toString(empty.getSum() / driven.getSum()));
                tripRecord.add(Double.toString(passengerTraveledDistance.getSum()));
                //tripRecord.add(Double.toString(driven.getMean()));
                //tripRecord.add(Double.toString(empty.getMean()));
                tripRecord.add(Double.toString(passengerTraveledDistance.getMean()));
                //tripRecord.add(Double.toString(d_p_d_t));


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
