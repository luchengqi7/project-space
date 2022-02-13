package org.matsim.project.prebookingStudy.jsprit.utils;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
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
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Time;

public class StatisticUtils {

    final static double PICKUP_SERVICE_TIME_IN_MATSIM = 60.;
    final static double DELIVERY_SERVICE_TIME_IN_MATSIM = 60.;

    final static Map<String, Double> waitingTimeMap = new HashMap<>();
    final static Map<String, Double> inVehicleTimeMap = new HashMap<>();
    final static Map<String, Double> travelTimeMap = new HashMap<>();
    final static Map<String, Double> travelDistanceMap = new HashMap<>();
    final static Map<String, Double> pickupTimeMap = new HashMap<>();
    final static Map<String, Double> deliveryTimeMap = new HashMap<>();

    public static void printVerbose(VehicleRoutingProblem problem, VehicleRoutingProblemSolution solution, String matsimConfig, String tripsFilename) {
        List<VehicleRoute> list = new ArrayList<>(solution.getRoutes());
        list.sort(new com.graphhopper.jsprit.core.util.VehicleIndexComparator());
        Map<String, Job> jobs = problem.getJobs();
        Map<String,Shipment> shipments = new HashMap<>();
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
            Location lastStopLocation = route.getStart().getLocation();
            TourActivity prevAct = route.getStart();
            for (TourActivity act : route.getActivities()) {
                String jobId;
                if (act instanceof TourActivity.JobActivity) {
                    jobId = ((TourActivity.JobActivity) act).getJob().getId();
                } else {
                    //ToDO: use other char?
                    jobId = "-";
                }
                double lastLegDistance = EuclideanDistanceCalculator.calculateDistance(lastStopLocation.getCoordinate(), act.getLocation().getCoordinate());
                if(("pickupShipment").equals(act.getName())){
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
                    realPickupDepartureTimeMap.put(jobId,act.getEndTime());


                    //distance-related:
                    if(routeTravelDistanceMap.containsKey(jobId)){
                        throw new RuntimeException("routeTravelDistanceMap.containsKey(jobId)");
                    } else {
                        routeTravelDistanceMap.replaceAll((s, v) -> v + lastLegDistance);
                        routeTravelDistanceMap.put(jobId, 0.);
                    }
                    lastStopLocation = act.getLocation();
                } else if(("deliverShipment").equals(act.getName())) {
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
                    if(routeTravelDistanceMap.containsKey(jobId)){
                        routeTravelDistanceMap.replaceAll((s, v) -> v + lastLegDistance);
                        travelDistanceMap.put(jobId, routeTravelDistanceMap.get(jobId));
                        routeTravelDistanceMap.remove(jobId);
                    } else {
                        throw new RuntimeException("!routeTravelDistanceMap.containsKey(jobId)");
                    }
                    lastStopLocation = act.getLocation();
                }
            }
        }
        //print results
        StatisticUtils statisticUtils = new StatisticUtils();
        statisticUtils.write(shipments, matsimConfig, tripsFilename);
    }

    public void write(Map<String, Shipment> shipments, String matsimConfig, String tripsFilename) {
        final String[] tripsHeader = {"person", "request_id", "pickup_time", "deliver_time", "in-veh_time", "trav_time", "wait_time", "traveled_distance", "euclidean_distance",
                "start_link", "start_x", "start_y", "end_link", "end_x", "end_y"};
        //ToDo: load config in Runner?
        String separator = ConfigUtils.loadConfig(matsimConfig).global().getDefaultDelimiter();

        //ToDo: do not use BufferedWriter
        try (CSVPrinter tripsCsvPrinter = new CSVPrinter(IOUtils.getBufferedWriter(tripsFilename),
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
                    for (String retval: shipment.getId().split("#", 2)){
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
                    tripRecord.add(Double.toString(/*(int) Math.round(distance)*/travelDistanceMap.get(shipmentId)));
                    tripRecord.add(Double.toString(euclideanDistance));

                    tripRecord.add(String.valueOf(shipment.getPickupLocation().getId()));
                    tripRecord.add(Double.toString(pickupLocation.getX()));
                    tripRecord.add(Double.toString(pickupLocation.getY()));
                    tripRecord.add(String.valueOf(shipment.getDeliveryLocation().getId()));
                    tripRecord.add(Double.toString(deliveryLocation.getX()));
                    tripRecord.add(Double.toString(deliveryLocation.getY()));
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

}
