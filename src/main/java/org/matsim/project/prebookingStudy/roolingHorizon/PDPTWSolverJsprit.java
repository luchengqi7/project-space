package org.matsim.project.prebookingStudy.roolingHorizon;

import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.PickupShipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.Solutions;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.project.prebookingStudy.jsprit.MatrixBasedVrpCosts;

import java.util.*;
import java.util.stream.Collectors;

public class PDPTWSolverJsprit {
    public static class Options {
        public final int maxIterations;
        public final boolean multiThread;

        public Options(int maxIterations, boolean multiThread) {
            this.maxIterations = maxIterations;
            this.multiThread = multiThread;
        }
    }

    private final Options options;
    private final DrtConfigGroup drtCfg;
    private final Network network;
    private final Map<Id<Link>, Location> locationByLinkId = new IdMap<>(Link.class);

    public PDPTWSolverJsprit(DrtConfigGroup drtCfg, Network network, Options options) {
        this.drtCfg = drtCfg;
        this.network = network;
        this.options = options;
    }

    public RollingHorizonDrtOptimizer.PreplannedSchedules calculate(Set<VehicleEntry> vehicleEntries,
                                                                    List<DrtRequest> newRequests,
                                                                    Map<VehicleEntry, List<AcceptedDrtRequest>> requestsOnboard,
                                                                    List<AcceptedDrtRequest> acceptedWaitingRequests,
                                                                    Map<Id<Request>, Double> updatedLatestPickUpTimeMap,
                                                                    Map<Id<Request>, Double> updatedLatestDropOffTimeMap) {
        // Create PDPTW problem
        var vrpBuilder = new VehicleRoutingProblem.Builder();
        // 1. Vehicle
        for (VehicleEntry vehicleEntry : vehicleEntries) {
            Link currentLink = vehicleEntry.start.link; //TODO Current divertable location for driving vehicles. Is this correct?
            double divertableTime = vehicleEntry.start.time; // TODO Is this correct?
            int capacity = vehicleEntry.vehicle.getCapacity();
            double serviceEndTime = vehicleEntry.vehicle.getServiceEndTime();
            var vehicleType = VehicleTypeImpl.Builder.newInstance(drtCfg.getMode() + "-vehicle")
                    .addCapacityDimension(0, capacity)
                    .build();

            var vehicleBuilder = VehicleImpl.Builder.newInstance(vehicleEntry.vehicle.getId() + "");
            vehicleBuilder.setEarliestStart(divertableTime);
            vehicleBuilder.setLatestArrival(serviceEndTime);
            vehicleBuilder.setStartLocation(collectLocationIfAbsent(currentLink));
            vehicleBuilder.setType(vehicleType);
            vehicleBuilder.addSkill(vehicleEntry.vehicle.getId().toString());
            // Vehicle skills can be used to make sure the request already onboard will be matched to the same vehicle

            vrpBuilder.addVehicle(vehicleBuilder.build());
        }

        // 2. Request
        var preplannedRequestByShipmentId = new HashMap<String, RollingHorizonDrtOptimizer.PreplannedRequest>();

        // 2.0 collect requests locations and compute the matrix
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(drtRequest.getFromLink()));
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(drtRequest.getToLink()));
        acceptedWaitingRequests.forEach(acceptedDrtRequest -> collectLocationIfAbsent(acceptedDrtRequest.getFromLink()));
        acceptedWaitingRequests.forEach(acceptedDrtRequest -> collectLocationIfAbsent(acceptedDrtRequest.getToLink()));
        for (List<AcceptedDrtRequest> acceptedDrtRequests : requestsOnboard.values()) {
            acceptedDrtRequests.forEach(acceptedDrtRequest -> collectLocationIfAbsent(acceptedDrtRequest.getToLink()));
        }

        var vrpCosts = MatrixBasedVrpCosts.calculateVrpCosts(network, locationByLinkId);
        vrpBuilder.setRoutingCost(vrpCosts);

        // 2.1 Passengers onboard
        for (VehicleEntry vehicleEntry : requestsOnboard.keySet()) {
            Link startLink = vehicleEntry.start.link;
            double time = vehicleEntry.start.time;
            String skill = vehicleEntry.vehicle.getId().toString(); // We use skill to lock the vehicle and request already onboard
            for (AcceptedDrtRequest requestOnboard : requestsOnboard.get(vehicleEntry)) {
                double latestArrivalTime = requestOnboard.getLatestArrivalTime();
                if (updatedLatestDropOffTimeMap.containsKey(requestOnboard.getId())) {
                    latestArrivalTime = updatedLatestDropOffTimeMap.get(requestOnboard.getId());
                }
                var shipmentId = requestOnboard.getId() + "_dummy_" + time;
                var shipment = Shipment.Builder.newInstance(shipmentId).
                        setPickupLocation(collectLocationIfAbsent(startLink)).
                        setDeliveryLocation(collectLocationIfAbsent(requestOnboard.getToLink())).
                        setPickupTimeWindow(new TimeWindow(time, time)).
                        setPickupServiceTime(0).
                        setDeliveryServiceTime(drtCfg.getStopDuration()).
                        setDeliveryTimeWindow(new TimeWindow(time, latestArrivalTime)).
                        addSizeDimension(0, 1).
                        addRequiredSkill(skill).
                        setPriority(1).
                        build();
                // Priority: 1 --> top priority. 10 --> the lowest priority
                vrpBuilder.addJob(shipment);

                var preplannedRequest = new RollingHorizonDrtOptimizer.PreplannedRequest(
                        new RollingHorizonDrtOptimizer.PreplannedRequestKey(requestOnboard.getPassengerId(), startLink.getId(), requestOnboard.getToLink().getId()),
                        time, time, latestArrivalTime);
                preplannedRequestByShipmentId.put(shipmentId, preplannedRequest);
            }
        }

        // 2.2 Already accepted requests (not yet picked up)
        for (AcceptedDrtRequest acceptedWaitingRequest : acceptedWaitingRequests) {
            double latestPickUpTime = acceptedWaitingRequest.getLatestStartTime();
            double latestArrivalTime = acceptedWaitingRequest.getLatestArrivalTime();
            if (updatedLatestPickUpTimeMap.containsKey(acceptedWaitingRequest.getId())) {
                latestPickUpTime = updatedLatestPickUpTimeMap.get(acceptedWaitingRequest.getId());
            }
            if (updatedLatestDropOffTimeMap.containsKey(acceptedWaitingRequest.getId())) {
                latestArrivalTime = updatedLatestDropOffTimeMap.get(acceptedWaitingRequest.getId());
            }
            var shipmentId = acceptedWaitingRequest.getId() + "_repeat"; //TODO add a unique identification element?
            var shipment = Shipment.Builder.newInstance(shipmentId).
                    setPickupLocation(collectLocationIfAbsent(acceptedWaitingRequest.getFromLink())).
                    setDeliveryLocation(collectLocationIfAbsent(acceptedWaitingRequest.getToLink())).
                    setPickupTimeWindow(new TimeWindow(acceptedWaitingRequest.getEarliestStartTime(), latestPickUpTime)).
                    setPickupServiceTime(drtCfg.getStopDuration()).
                    setDeliveryServiceTime(drtCfg.getStopDuration()).
                    setDeliveryTimeWindow(new TimeWindow(acceptedWaitingRequest.getEarliestStartTime(), latestArrivalTime)).
                    addSizeDimension(0, 1).
                    setPriority(1).
                    build();
            // Priority: 1 --> top priority. 10 --> the lowest priority
            vrpBuilder.addJob(shipment);

            var preplannedRequest = new RollingHorizonDrtOptimizer.PreplannedRequest(
                    new RollingHorizonDrtOptimizer.PreplannedRequestKey(acceptedWaitingRequest.getPassengerId(), acceptedWaitingRequest.getFromLink().getId(), acceptedWaitingRequest.getToLink().getId()),
                    acceptedWaitingRequest.getEarliestStartTime(), latestPickUpTime, latestArrivalTime);
            preplannedRequestByShipmentId.put(shipmentId, preplannedRequest);
        }

        // 2.3 New requests
        for (DrtRequest newRequest : newRequests) {
            var shipmentId = newRequest.getId().toString();
            var shipment = Shipment.Builder.newInstance(shipmentId).
                    setPickupLocation(collectLocationIfAbsent(newRequest.getFromLink())).
                    setDeliveryLocation(collectLocationIfAbsent(newRequest.getToLink())).
                    setPickupTimeWindow(new TimeWindow(newRequest.getEarliestStartTime(), newRequest.getLatestStartTime())).
                    setDeliveryTimeWindow(new TimeWindow(newRequest.getEarliestStartTime(), newRequest.getLatestArrivalTime())).
                    setPickupServiceTime(drtCfg.getStopDuration()).
                    setDeliveryServiceTime(drtCfg.getStopDuration()).
                    addSizeDimension(0, 1).
                    setPriority(2).
                    build();
            vrpBuilder.addJob(shipment);

            var preplannedRequest = new RollingHorizonDrtOptimizer.PreplannedRequest(
                    new RollingHorizonDrtOptimizer.PreplannedRequestKey(newRequest.getPassengerId(), newRequest.getFromLink().getId(), newRequest.getToLink().getId()),
                    newRequest.getEarliestStartTime(), newRequest.getLatestStartTime(),
                    newRequest.getLatestArrivalTime());
            preplannedRequestByShipmentId.put(shipmentId, preplannedRequest);
        }

        // Solve VRP problem
        var problem = vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE).build();
        String numOfThreads = "1";
        if (options.multiThread) {
            numOfThreads = Runtime.getRuntime().availableProcessors() + "";
        }
        var algorithm = Jsprit.Builder.newInstance(problem)
                .setProperty(Jsprit.Parameter.THREADS, numOfThreads)
                .buildAlgorithm();
        algorithm.setMaxIterations(options.maxIterations);
        var solutions = algorithm.searchSolutions();
        var bestSolution = Solutions.bestOf(solutions);

        // Collect results
        List<Id<Person>> personsOnboard = new ArrayList<>();
        List<AcceptedDrtRequest> acceptedDrtRequests = new ArrayList<>();
        requestsOnboard.values().forEach(acceptedDrtRequests::addAll);
        acceptedDrtRequests.forEach(r -> personsOnboard.add(r.getPassengerId()));

        Map<RollingHorizonDrtOptimizer.PreplannedRequestKey, Id<DvrpVehicle>> preplannedRequestToVehicle = new HashMap<>();
        Map<Id<DvrpVehicle>, Queue<RollingHorizonDrtOptimizer.PreplannedStop>> vehicleToPreplannedStops = problem.getVehicles()
                .stream()
                .collect(Collectors.toMap(v -> Id.create(v.getId(), DvrpVehicle.class), v -> new LinkedList<>()));

        for (var route : bestSolution.getRoutes()) {
            var vehicleId = Id.create(route.getVehicle().getId(), DvrpVehicle.class);
            for (var activity : route.getActivities()) {
                var preplannedRequest = preplannedRequestByShipmentId.get(((TourActivity.JobActivity) activity).getJob().getId());

                boolean isPickup = activity instanceof PickupShipment;
                if (isPickup) {
                    preplannedRequestToVehicle.put(preplannedRequest.key(), vehicleId);
                }

                if (personsOnboard.contains(preplannedRequest.key().passengerId()) && isPickup) {
                    continue; // For passengers already onboard, there will be no extra pick up stop
                }

                //act -> preplanned stop
                var preplannedStop = new RollingHorizonDrtOptimizer.PreplannedStop(preplannedRequest, isPickup);
                vehicleToPreplannedStops.get(vehicleId).add(preplannedStop);
            }
        }

        Map<RollingHorizonDrtOptimizer.PreplannedRequestKey, RollingHorizonDrtOptimizer.PreplannedRequest> unassignedRequests = new HashMap<>();
        for (Job job : bestSolution.getUnassignedJobs()) {
            RollingHorizonDrtOptimizer.PreplannedRequest rejectedRequest = preplannedRequestByShipmentId.get(job.getId());
            unassignedRequests.put(rejectedRequest.key(), rejectedRequest);
        }

        return new RollingHorizonDrtOptimizer.PreplannedSchedules(preplannedRequestToVehicle, vehicleToPreplannedStops, unassignedRequests);
    }

    private Location collectLocationIfAbsent(Link link) {
        return locationByLinkId.computeIfAbsent(link.getId(), linkId -> Location.Builder.newInstance()
                .setId(link.getId() + "")
                .setIndex(locationByLinkId.size())
                .setCoordinate(Coordinate.newInstance(link.getCoord().getX(), link.getCoord().getY()))
                .build());
    }

}
