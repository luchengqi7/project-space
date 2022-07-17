package org.matsim.project.prebookingStudy.rollingHorizon;

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
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
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
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.schedule.DriveTask;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.contrib.dvrp.tracker.OnlineDriveTaskTracker;
import org.matsim.contrib.dvrp.util.LinkTimePair;
import org.matsim.project.prebookingStudy.jsprit.MatrixBasedVrpCosts;

import java.util.*;
import java.util.stream.Collectors;

public class PDPTWSolverJsprit {
    public record Options(int maxIterations, boolean multiThread) {
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

    public RollingHorizonDrtOptimizer.PreplannedSchedules calculate(Map<DvrpVehicle, RollingHorizonDrtOptimizer.OnlineVehicleInfo> realTimeVehicleInfoMap,
                                                                    List<DrtRequest> newRequests,
                                                                    Map<RollingHorizonDrtOptimizer.OnlineVehicleInfo, List<RollingHorizonDrtOptimizer.PreplannedRequest>> requestsOnboard,
                                                                    List<RollingHorizonDrtOptimizer.PreplannedRequest> acceptedWaitingRequests,
                                                                    Map<Id<Request>, Double> updatedLatestPickUpTimeMap,
                                                                    Map<Id<Request>, Double> updatedLatestDropOffTimeMap) {
        // Create PDPTW problem
        var vrpBuilder = new VehicleRoutingProblem.Builder();
        // 1. Vehicle
        for (RollingHorizonDrtOptimizer.OnlineVehicleInfo vehicleInfo : realTimeVehicleInfoMap.values()) {
            DvrpVehicle vehicle = vehicleInfo.vehicle();
            Link currentLink = vehicleInfo.currentLink();
            double divertableTime = vehicleInfo.divertableTime();

            int capacity = vehicle.getCapacity();
            double serviceEndTime = vehicle.getServiceEndTime();
            var vehicleType = VehicleTypeImpl.Builder.newInstance(drtCfg.getMode() + "-vehicle")
                    .addCapacityDimension(0, capacity)
                    .build();

            var vehicleBuilder = VehicleImpl.Builder.newInstance(vehicle.getId() + "");
            vehicleBuilder.setEarliestStart(divertableTime);
            vehicleBuilder.setLatestArrival(serviceEndTime);
            vehicleBuilder.setStartLocation(collectLocationIfAbsent(currentLink));
            vehicleBuilder.setType(vehicleType);
            vehicleBuilder.addSkill(vehicle.getId().toString()); // Vehicle skills can be used to make sure the request already onboard will be matched to the same vehicle
            vrpBuilder.addVehicle(vehicleBuilder.build());
        }

        // 2. Request
        var preplannedRequestByShipmentId = new HashMap<String, RollingHorizonDrtOptimizer.PreplannedRequest>();

        // 2.0 collect requests locations and compute the matrix
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(drtRequest.getFromLink()));
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(drtRequest.getToLink()));
        acceptedWaitingRequests.forEach(acceptedDrtRequest -> collectLocationIfAbsent(network.getLinks().get(acceptedDrtRequest.key().fromLinkId())));
        acceptedWaitingRequests.forEach(acceptedDrtRequest -> collectLocationIfAbsent(network.getLinks().get(acceptedDrtRequest.key().toLinkId())));
        for (List<RollingHorizonDrtOptimizer.PreplannedRequest> requestOnboard : requestsOnboard.values()) {
            requestOnboard.forEach(acceptedDrtRequest -> collectLocationIfAbsent(network.getLinks().get(acceptedDrtRequest.key().toLinkId())));
        }

        var vrpCosts = MatrixBasedVrpCosts.calculateVrpCosts(network, locationByLinkId);
        vrpBuilder.setRoutingCost(vrpCosts);

        // 2.1 Passengers onboard
        for (RollingHorizonDrtOptimizer.OnlineVehicleInfo vehicleInfo : requestsOnboard.keySet()) {
            Link startLink = vehicleInfo.currentLink();
            double time = vehicleInfo.divertableTime();
            String skill = vehicleInfo.vehicle().getId().toString(); // We use skill to lock the vehicle and request already onboard
            for (RollingHorizonDrtOptimizer.PreplannedRequest requestOnboard : requestsOnboard.get(vehicleInfo)) {
                double latestArrivalTime = requestOnboard.latestArrivalTime();
                if (updatedLatestDropOffTimeMap.containsKey(requestOnboard.key().passengerId())) {
                    latestArrivalTime = updatedLatestDropOffTimeMap.get(requestOnboard.key().passengerId());
                }
                var shipmentId = requestOnboard.key().passengerId().toString() + "_dummy_" + time;
                var shipment = Shipment.Builder.newInstance(shipmentId).
                        setPickupLocation(collectLocationIfAbsent(startLink)).
                        setDeliveryLocation(collectLocationIfAbsent(network.getLinks().get(requestOnboard.key().toLinkId()))).
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
                        new RollingHorizonDrtOptimizer.PreplannedRequestKey(requestOnboard.key().passengerId(), startLink.getId(), requestOnboard.key().toLinkId()),
                        time, time, latestArrivalTime);
                preplannedRequestByShipmentId.put(shipmentId, preplannedRequest);
            }
        }

        // 2.2 Already accepted requests (not yet picked up)
        for (RollingHorizonDrtOptimizer.PreplannedRequest acceptedWaitingRequest : acceptedWaitingRequests) {
            double latestPickUpTime = acceptedWaitingRequest.latestStartTime();
            double latestArrivalTime = acceptedWaitingRequest.latestArrivalTime();
            if (updatedLatestPickUpTimeMap.containsKey(acceptedWaitingRequest.key().passengerId())) {
                latestPickUpTime = updatedLatestPickUpTimeMap.get(acceptedWaitingRequest.key().passengerId());
            }
            if (updatedLatestDropOffTimeMap.containsKey(acceptedWaitingRequest.key().passengerId())) {
                latestArrivalTime = updatedLatestDropOffTimeMap.get(acceptedWaitingRequest.key().passengerId());
            }
            var shipmentId = acceptedWaitingRequest.key().passengerId() + "_repeat";
            var shipment = Shipment.Builder.newInstance(shipmentId).
                    setPickupLocation(collectLocationIfAbsent(network.getLinks().get(acceptedWaitingRequest.key().fromLinkId()))).
                    setDeliveryLocation(collectLocationIfAbsent(network.getLinks().get(acceptedWaitingRequest.key().toLinkId()))).
                    setPickupTimeWindow(new TimeWindow(acceptedWaitingRequest.earliestStartTime(), latestPickUpTime)).
                    setPickupServiceTime(drtCfg.getStopDuration()).
                    setDeliveryServiceTime(drtCfg.getStopDuration()).
                    setDeliveryTimeWindow(new TimeWindow(acceptedWaitingRequest.latestStartTime(), latestArrivalTime)).
                    addSizeDimension(0, 1).
                    setPriority(1).
                    build();
            // Priority: 1 --> top priority. 10 --> the lowest priority
            vrpBuilder.addJob(shipment);

            var preplannedRequest = new RollingHorizonDrtOptimizer.PreplannedRequest(
                    new RollingHorizonDrtOptimizer.PreplannedRequestKey(acceptedWaitingRequest.key().passengerId(),
                            acceptedWaitingRequest.key().fromLinkId(), acceptedWaitingRequest.key().toLinkId()),
                    acceptedWaitingRequest.earliestStartTime(), latestPickUpTime, latestArrivalTime);
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

            var preplannedRequest = RollingHorizonDrtOptimizer.createFromRequest(newRequest);
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

        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE); // TODO delete

        // Collect results
        List<Id<Person>> personsOnboard = new ArrayList<>();
        List<RollingHorizonDrtOptimizer.PreplannedRequest> listOfRequestsOnboard = new ArrayList<>();
        requestsOnboard.values().forEach(listOfRequestsOnboard::addAll);
        listOfRequestsOnboard.forEach(r -> personsOnboard.add(r.key().passengerId()));

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
