package org.matsim.project.prebookingStudy.jsprit.utils;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.EuclideanDistanceCalculator;

import java.util.*;

//regarding printing csv
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StatisticCollectorForOF {

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

    public Map<String, Double> getTravelTimeMap() {
        return travelTimeMap;
    }
    public Map<String, Double> getPassengerTraveledDistanceMap() {
        return passengerTraveledDistanceMap;
    }
    public Map<String, Double> getWaitingTimeMap() {
        return waitingTimeMap;
    }
    public Map<String, Double> getPickupTimeMap() {
        return pickupTimeMap;
    }
    public Map<String, Double> getDeliveryTimeMap() {
        return deliveryTimeMap;
    }

    Map<String, Double> desiredPickupTimeMap = new HashMap<>();
    Map<String, Double> desiredDeliveryTimeMap = new HashMap<>();
    public void setDesiredPickupTimeMap(Map<String, Double> desiredPickupTimeMap) {
        this.desiredPickupTimeMap = desiredPickupTimeMap;
    }
    public void setDesiredDeliveryTimeMap(Map<String, Double> desiredDeliveryTimeMap) {
        this.desiredDeliveryTimeMap = desiredDeliveryTimeMap;
    }
    public Map<String, Double> getDesiredPickupTimeMap() {
        return desiredPickupTimeMap;
    }
    public Map<String, Double> getDesiredDeliveryTimeMap() {
        return desiredDeliveryTimeMap;
    }


    public StatisticCollectorForOF(VehicleRoutingTransportCosts transportCosts, double ServiceTimeInMatsim) {
        this.enableNetworkBasedCosts = true;
        this.transportCosts = transportCosts;
        this.serviceTimeInMatsim = ServiceTimeInMatsim;
    }
    public StatisticCollectorForOF(double ServiceTimeInMatsim) {
        this.enableNetworkBasedCosts = false;
        this.serviceTimeInMatsim = ServiceTimeInMatsim;
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
                    }
                }
            }
            TourActivity afterAct = route.getEnd();
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
}
