package org.matsim.project.drtOperationStudy.rollingHorizon;

import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
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
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.project.drtSchoolTransportStudy.jsprit.MatrixBasedVrpCosts;

import java.util.*;
import java.util.stream.Collectors;

public class PDPTWSolverJsprit {
    public record Options(int maxIterations, boolean multiThread, Random random) {
    }

    private final Options options;
    private final DrtConfigGroup drtCfg;
    private final Network network;
    private final Map<Id<Link>, Location> locationByLinkId = new IdMap<>(Link.class);

    public static final double REJECTION_COST = 100000;

    public PDPTWSolverJsprit(DrtConfigGroup drtCfg, Network network, Options options) {
        this.drtCfg = drtCfg;
        this.network = network;
        this.options = options;
    }

    public RollingHorizonDrtOptimizer.PreplannedSchedules calculate(RollingHorizonDrtOptimizer.PreplannedSchedules previousSchedule,
                                                                    Map<Id<DvrpVehicle>, RollingHorizonDrtOptimizer.OnlineVehicleInfo> realTimeVehicleInfoMap,
                                                                    List<DrtRequest> newRequests) {
        // Create PDPTW problem
        var vrpBuilder = new VehicleRoutingProblem.Builder();
        // 1. Vehicle
        Map<Id<DvrpVehicle>, VehicleImpl> vehicleIdToJSpritVehicleMap = new HashMap<>();
        for (RollingHorizonDrtOptimizer.OnlineVehicleInfo vehicleInfo : realTimeVehicleInfoMap.values()) {
            DvrpVehicle vehicle = vehicleInfo.vehicle();
            Link currentLink = vehicleInfo.currentLink();
            double divertableTime = vehicleInfo.divertableTime();

            int capacity = vehicle.getCapacity();
            var vehicleType = VehicleTypeImpl.Builder.newInstance(drtCfg.getMode() + "-vehicle-" + capacity + "-seats")
                    .addCapacityDimension(0, capacity)
                    .build();
            double serviceEndTime = vehicle.getServiceEndTime();
            var vehicleBuilder = VehicleImpl.Builder.newInstance(vehicle.getId() + "");
            vehicleBuilder.setEarliestStart(divertableTime);
            vehicleBuilder.setLatestArrival(serviceEndTime);
            vehicleBuilder.setStartLocation(collectLocationIfAbsent(currentLink));
            vehicleBuilder.setReturnToDepot(false);
            vehicleBuilder.setType(vehicleType);
            vehicleBuilder.addSkill(vehicle.getId().toString()); // Vehicle skills can be used to make sure the request already onboard will be matched to the same vehicle
            VehicleImpl jSpritVehicle = vehicleBuilder.build();
            vrpBuilder.addVehicle(jSpritVehicle);
            vehicleIdToJSpritVehicleMap.put(vehicle.getId(), jSpritVehicle);
        }

        // 2. Request
        var preplannedRequestByShipmentId = new HashMap<String, RollingHorizonDrtOptimizer.PreplannedRequest>();
        List<RollingHorizonDrtOptimizer.PreplannedRequest> requestsOnboard = new ArrayList<>();

        // 2.0 collect requests locations and compute the matrix
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(drtRequest.getFromLink()));
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(drtRequest.getToLink()));
        if (previousSchedule != null) {
            for (Id<DvrpVehicle> vehicleId : previousSchedule.vehicleToPreplannedStops().keySet()) {
                for (RollingHorizonDrtOptimizer.PreplannedStop stop : previousSchedule.vehicleToPreplannedStops().get(vehicleId)) {
                    if (stop.pickup()) {
                        Id<Link> fromLinkId = stop.preplannedRequest().key().fromLinkId();
                        collectLocationIfAbsent(network.getLinks().get(fromLinkId));
                    } else {
                        Id<Link> toLinkId = stop.preplannedRequest().key().toLinkId();
                        collectLocationIfAbsent(network.getLinks().get(toLinkId));
                    }
                }
            }
        }
        var vrpCosts = MatrixBasedVrpCosts.calculateVrpCosts(network, locationByLinkId);  // TODO: @Michal we may need to update the TravelTime
        vrpBuilder.setRoutingCost(vrpCosts);

        // 2.1 Passengers already assigned
        // When creating the shipment, we may need to postpone the pickup/delivery deadlines, in order to keep the original solution still remains feasible (due to potential delays)
        if (previousSchedule != null) {
            for (Id<DvrpVehicle> vehicleId : previousSchedule.vehicleToPreplannedStops().keySet()) {
                Map<RollingHorizonDrtOptimizer.PreplannedRequest, Double> requestPickUpTimeMap = new HashMap<>();
                Map<RollingHorizonDrtOptimizer.PreplannedRequest, Shipment> requestToShipmentMap = new HashMap<>();
                List<RollingHorizonDrtOptimizer.PreplannedRequest> requestsOnboardThisVehicle = new ArrayList<>();

                Link vehicleStartLink = realTimeVehicleInfoMap.get(vehicleId).currentLink();
                double vehicleStartTime = realTimeVehicleInfoMap.get(vehicleId).divertableTime();
                Link currentLink = vehicleStartLink;
                double currentTime = vehicleStartTime;

                for (RollingHorizonDrtOptimizer.PreplannedStop stop : previousSchedule.vehicleToPreplannedStops().get(vehicleId)) {
                    Link stopLink;
                    RollingHorizonDrtOptimizer.PreplannedRequest request = stop.preplannedRequest();
                    if (stop.pickup()) {
                        // This is an already accepted request, we will record the updated the earliest "latest pick-up time" (i.e., due to delay, the latest pick-up time may need to be extended)
                        // We still need the drop-off information to generate the shipment
                        stopLink = network.getLinks().get(request.key().fromLinkId());
                        double travelTime = vrpCosts.getTransportTime(collectLocationIfAbsent(currentLink), collectLocationIfAbsent(stopLink), currentTime, null, null);
                        currentTime += travelTime;
                        currentLink = stopLink;
                        requestPickUpTimeMap.put(request, currentTime);
                    } else {
                        // Now we have the drop-off information, we can generate special shipments for already accepted requests
                        stopLink = network.getLinks().get(request.key().toLinkId());
                        double travelTime = vrpCosts.getTransportTime(collectLocationIfAbsent(currentLink), collectLocationIfAbsent(stopLink), currentTime, null, null);
                        currentTime += travelTime;
                        currentLink = stopLink;

                        double earliestLatestDropOffTime = currentTime;
                        if (!requestPickUpTimeMap.containsKey(request)) {
                            // The request is already onboard
                            requestsOnboardThisVehicle.add(request);
                            var shipmentId = request.key().passengerId().toString() + "_dummy_" + vehicleStartTime;
                            var shipment = Shipment.Builder.newInstance(shipmentId).
                                    setPickupLocation(collectLocationIfAbsent(vehicleStartLink)).
                                    setDeliveryLocation(collectLocationIfAbsent(network.getLinks().get(request.key().toLinkId()))).
                                    setPickupTimeWindow(new TimeWindow(vehicleStartTime, vehicleStartTime)).
                                    setPickupServiceTime(0).
                                    setDeliveryServiceTime(drtCfg.stopDuration).
                                    setDeliveryTimeWindow(new TimeWindow(vehicleStartTime, Math.max(request.latestArrivalTime(), earliestLatestDropOffTime))).
                                    addSizeDimension(0, 1).
                                    addRequiredSkill(vehicleId.toString()).
                                    setPriority(1).
                                    build();
                            // Priority: 1 --> top priority. 10 --> the lowest priority
                            vrpBuilder.addJob(shipment);
                            requestToShipmentMap.put(request, shipment);
                            preplannedRequestByShipmentId.put(shipmentId, request);
                        } else {
                            // The request is waiting to be picked up: retrieve the earliestLatestPickUpTime
                            double earliestLatestPickUpTime = requestPickUpTimeMap.get(request);

                            var shipmentId = request.key().passengerId().toString() + "_repeat_" + vehicleStartTime;
                            var shipment = Shipment.Builder.newInstance(shipmentId).
                                    setPickupLocation(collectLocationIfAbsent(network.getLinks().get(request.key().fromLinkId()))).
                                    setDeliveryLocation(collectLocationIfAbsent(network.getLinks().get(request.key().toLinkId()))).
                                    setPickupTimeWindow(new TimeWindow(request.earliestStartTime(), Math.max(request.latestStartTime(), earliestLatestPickUpTime))).
                                    setPickupServiceTime(drtCfg.stopDuration).
                                    setDeliveryServiceTime(drtCfg.stopDuration).
                                    setDeliveryTimeWindow(new TimeWindow(vehicleStartTime, Math.max(request.latestArrivalTime(), earliestLatestDropOffTime))).
                                    addSizeDimension(0, 1).
                                    setPriority(1).
                                    build();
                            // Priority: 1 --> top priority. 10 --> the lowest priority
                            vrpBuilder.addJob(shipment);
                            requestToShipmentMap.put(request, shipment);
                            preplannedRequestByShipmentId.put(shipmentId, request);
                        }
                    }
                    currentTime += drtCfg.stopDuration;
                }

                // Now we need to create the initial route for this vehicle for the VRP problem
                VehicleRoute.Builder iniRouteBuilder = VehicleRoute.Builder.newInstance(vehicleIdToJSpritVehicleMap.get(vehicleId));
                // First pick up the dummy requests (onboard request)
                for (RollingHorizonDrtOptimizer.PreplannedRequest requestOnboardThisVehicle : requestsOnboardThisVehicle) {
                    iniRouteBuilder.addPickup(requestToShipmentMap.get(requestOnboardThisVehicle));
                }
                // Then deliver those requests based on the previous stop plans
                for (RollingHorizonDrtOptimizer.PreplannedStop stop : previousSchedule.vehicleToPreplannedStops().get(vehicleId)) {
                    if (requestsOnboardThisVehicle.contains(stop.preplannedRequest())) {
                        Shipment shipment = requestToShipmentMap.get(stop.preplannedRequest());
                        iniRouteBuilder.addDelivery(shipment);
                    }
                }
                VehicleRoute iniRoute = iniRouteBuilder.build();
                vrpBuilder.addInitialVehicleRoute(iniRoute);

                // Add the request onboard this vehicle to the main pool
                requestsOnboard.addAll(requestsOnboardThisVehicle);
            }
        }

        // 2.2 New requests
        for (DrtRequest newRequest : newRequests) {
            var shipmentId = newRequest.getId().toString();
            var shipment = Shipment.Builder.newInstance(shipmentId).
                    setPickupLocation(collectLocationIfAbsent(newRequest.getFromLink())).
                    setDeliveryLocation(collectLocationIfAbsent(newRequest.getToLink())).
                    setPickupTimeWindow(new TimeWindow(newRequest.getEarliestStartTime(), newRequest.getLatestStartTime())).
                    setDeliveryTimeWindow(new TimeWindow(newRequest.getEarliestStartTime(), newRequest.getLatestArrivalTime())).
                    setPickupServiceTime(drtCfg.stopDuration).
                    setDeliveryServiceTime(drtCfg.stopDuration).
                    addSizeDimension(0, 1).
                    setPriority(10).
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
                .setObjectiveFunction(new DefaultRollingHorizonObjectiveFunction(problem))
                .setRandom(options.random)
                .buildAlgorithm();
        algorithm.setMaxIterations(options.maxIterations);
        var solutions = algorithm.searchSolutions();
        var bestSolution = Solutions.bestOf(solutions);

        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE); // TODO delete

        // Collect results
        List<Id<Person>> personsOnboard = new ArrayList<>();
        requestsOnboard.forEach(r -> personsOnboard.add(r.key().passengerId()));

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

    private record DefaultRollingHorizonObjectiveFunction(VehicleRoutingProblem vrp) implements SolutionCostCalculator {
        @Override
        public double getCosts(VehicleRoutingProblemSolution solution) {
            double costs = 0;
            for (VehicleRoute route : solution.getRoutes()) {
                costs += route.getVehicle().getType().getVehicleCostParams().fix;
                TourActivity prevAct = route.getStart();
                for (TourActivity act : route.getActivities()) {
                    costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    costs += vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                    prevAct = act;
                }
            }

            for (Job j : solution.getUnassignedJobs()) {
                costs += REJECTION_COST * (11 - j.getPriority());
            }

            return costs;
        }
    }

}
