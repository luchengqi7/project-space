package org.matsim.project.drtOperationStudy.mixedCase;

import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.PickupShipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.Solutions;
import one.util.streamex.EntryStream;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.zone.Zone;
import org.matsim.contrib.zone.skims.Matrix;
import org.matsim.contrib.zone.skims.TravelTimeMatrices;
import org.matsim.contrib.zone.skims.TravelTimeMatrix;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static org.matsim.contrib.dvrp.path.VrpPaths.FIRST_LINK_TT;

class PrebookedRequestsSolverJsprit implements PrebookedRequestsSolver{
    private final Options options;
    private final DrtConfigGroup drtCfg;
    private final Network network;
    private final TravelTime travelTime;
    private final TravelDisutility travelDisutility;
    private final Map<Id<Link>, Location> locationByLinkId = new IdMap<>(Link.class);

    public static final double REJECTION_COST = 100000;


    PrebookedRequestsSolverJsprit(Options options, DrtConfigGroup drtCfg, Network network, TravelTime travelTime) {
        this.options = options;
        this.drtCfg = drtCfg;
        this.network = network;
        this.travelTime = travelTime;
        this.travelDisutility = new TimeAsTravelDisutility(travelTime);
    }

    @Override
    public MixedCaseDrtOptimizer.FleetSchedules calculate(MixedCaseDrtOptimizer.FleetSchedules previousSchedules,
                                                   Map<Id<DvrpVehicle>, MixedCaseDrtOptimizer.OnlineVehicleInfo> onlineVehicleInfoMap,
                                                   List<MixedCaseDrtOptimizer.GeneralRequest> newRequests,
                                                   double time) {
        locationByLinkId.clear();
        // Create PDPTW problem
        var vrpBuilder = new VehicleRoutingProblem.Builder();
        // 1. Vehicle
        Map<Id<DvrpVehicle>, VehicleImpl> vehicleIdToJSpritVehicleMap = new HashMap<>();
        for (MixedCaseDrtOptimizer.OnlineVehicleInfo vehicleInfo : onlineVehicleInfoMap.values()) {
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
        var preplannedRequestByShipmentId = new HashMap<String, MixedCaseDrtOptimizer.GeneralRequest>();
        Map<Id<DvrpVehicle>, List<MixedCaseDrtOptimizer.GeneralRequest>> requestsOnboardEachVehicles = new HashMap<>();
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(network.getLinks().get(drtRequest.fromLinkId())));
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(network.getLinks().get(drtRequest.toLinkId())));

        if (previousSchedules != null) {
            for (Id<DvrpVehicle> vehicleId : previousSchedules.vehicleToTimetableMap().keySet()) {
                for (TimetableEntry stop : previousSchedules.vehicleToTimetableMap().get(vehicleId)) {
                    if (stop.stopType == TimetableEntry.StopType.PICKUP) {
                        Id<Link> fromLinkId = stop.getRequest().fromLinkId();
                        collectLocationIfAbsent(network.getLinks().get(fromLinkId));
                    } else {
                        Id<Link> toLinkId = stop.getRequest().toLinkId();
                        collectLocationIfAbsent(network.getLinks().get(toLinkId));
                    }
                }
            }
        }

        // Calculate link to link travel time matrix and initialize VRP costs
        TravelTimeMatrix travelTimeMatrix = createTravelTimeMatrix(time);
        MatrixBasedVrpCosts vrpCosts = new MatrixBasedVrpCosts(travelTimeMatrix, time, network, travelTime);
        vrpBuilder.setRoutingCost(vrpCosts);
        List<VehicleRoute> routesForInitialSolutions = new ArrayList<>();
        List<Job> unassignedShipments = new ArrayList<>(); //Used for initial solution

        Map<MixedCaseDrtOptimizer.GeneralRequest, Shipment> requestToShipmentMap = new HashMap<>();
        // 2.1 Passengers already assigned
        // When creating the shipment, we may need to postpone the pickup/delivery deadlines, in order to keep the original solution still remains feasible (due to potential delays)
        if (previousSchedules != null) {
            for (Id<DvrpVehicle> vehicleId : previousSchedules.vehicleToTimetableMap().keySet()) {
                Map<MixedCaseDrtOptimizer.GeneralRequest, Double> requestPickUpTimeMap = new HashMap<>();
                List<MixedCaseDrtOptimizer.GeneralRequest> requestsOnboardThisVehicle = new ArrayList<>();
                Link vehicleStartLink = onlineVehicleInfoMap.get(vehicleId).currentLink();
                double vehicleStartTime = onlineVehicleInfoMap.get(vehicleId).divertableTime();
                Link currentLink = vehicleStartLink;
                double currentTime = vehicleStartTime;

                for (TimetableEntry stop : previousSchedules.vehicleToTimetableMap().get(vehicleId)) {
                    Link stopLink;
                    MixedCaseDrtOptimizer.GeneralRequest request = stop.getRequest();
                    if (stop.stopType == TimetableEntry.StopType.PICKUP) {
                        // This is an already accepted request, we will record the updated the earliest "latest pick-up time" (i.e., due to delay, the latest pick-up time may need to be extended)
                        // We still need the drop-off information to generate the shipment
                        stopLink = network.getLinks().get(request.fromLinkId());
                        double travelTime = vrpCosts.getTransportTime(collectLocationIfAbsent(currentLink), collectLocationIfAbsent(stopLink), currentTime, null, null);
                        currentTime += travelTime;
                        currentLink = stopLink;
                        requestPickUpTimeMap.put(request, currentTime);
                    } else {
                        // Now we have the drop-off information, we can generate special shipments for already accepted requests
                        stopLink = network.getLinks().get(request.toLinkId());
                        double travelTime = vrpCosts.getTransportTime(collectLocationIfAbsent(currentLink), collectLocationIfAbsent(stopLink), currentTime, null, null);
                        currentTime += travelTime;
                        currentLink = stopLink;

                        double earliestLatestDropOffTime = currentTime;
                        if (!requestPickUpTimeMap.containsKey(request)) {
                            // The request is already onboard
                            requestsOnboardThisVehicle.add(request);
                            var shipmentId = request.passengerId().toString() + "_dummy_" + vehicleStartTime;
                            var shipment = Shipment.Builder.newInstance(shipmentId).
                                    setPickupLocation(collectLocationIfAbsent(vehicleStartLink)).
                                    setDeliveryLocation(collectLocationIfAbsent(network.getLinks().get(request.toLinkId()))).
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

                            var shipmentId = request.passengerId().toString() + "_repeat_" + vehicleStartTime;
                            var shipment = Shipment.Builder.newInstance(shipmentId).
                                    setPickupLocation(collectLocationIfAbsent(network.getLinks().get(request.fromLinkId()))).
                                    setDeliveryLocation(collectLocationIfAbsent(network.getLinks().get(request.toLinkId()))).
                                    setPickupTimeWindow(new TimeWindow(request.earliestStartTime(), Math.max(request.latestStartTime(), earliestLatestPickUpTime))).
                                    setPickupServiceTime(drtCfg.stopDuration).
                                    setDeliveryServiceTime(drtCfg.stopDuration).
                                    setDeliveryTimeWindow(new TimeWindow(vehicleStartTime, Math.max(request.latestArrivalTime(), earliestLatestDropOffTime))).
                                    addSizeDimension(0, 1).
                                    setPriority(2).
                                    build();
                            // Priority: 1 --> top priority. 10 --> the lowest priority
                            vrpBuilder.addJob(shipment);
                            requestToShipmentMap.put(request, shipment);
                            preplannedRequestByShipmentId.put(shipmentId, request);
                        }
                    }
                    currentTime += drtCfg.stopDuration;
                }
                // Add the request onboard this vehicle to the main pool
                requestsOnboardEachVehicles.put(vehicleId, requestsOnboardThisVehicle);
            }
        }

        // 2.2 New requests
        for (MixedCaseDrtOptimizer.GeneralRequest newRequest : newRequests) {
            var shipmentId = newRequest.passengerId().toString();
            var shipment = Shipment.Builder.newInstance(shipmentId).
                    setPickupLocation(collectLocationIfAbsent(network.getLinks().get(newRequest.fromLinkId()))).
                    setDeliveryLocation(collectLocationIfAbsent(network.getLinks().get(newRequest.toLinkId()))).
                    setPickupTimeWindow(new TimeWindow(newRequest.earliestStartTime(), newRequest.latestStartTime())).
                    setDeliveryTimeWindow(new TimeWindow(newRequest.earliestStartTime(), newRequest.latestArrivalTime())).
                    setPickupServiceTime(drtCfg.stopDuration).
                    setDeliveryServiceTime(drtCfg.stopDuration).
                    addSizeDimension(0, 1).
                    setPriority(10).
                    build();
            vrpBuilder.addJob(shipment);
            preplannedRequestByShipmentId.put(shipmentId, newRequest);
            unassignedShipments.add(shipment);
        }

        // Solve VRP problem
        var problem = vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE).build();

        String numOfThreads = "1";
        if (options.multiThread) {
            numOfThreads = Runtime.getRuntime().availableProcessors() + "";
        }
        if (previousSchedules != null) {
            for (Id<DvrpVehicle> vehicleId : previousSchedules.vehicleToTimetableMap().keySet()) {
                // Now we need to create the initial solution for this vehicle for the VRP problem
                VehicleRoute.Builder initialRouteBuilder = VehicleRoute.Builder
                        .newInstance(vehicleIdToJSpritVehicleMap.get(vehicleId))
                        .setJobActivityFactory(problem.getJobActivityFactory());
                // First pick up all the dummy requests (onboard request)
                for (MixedCaseDrtOptimizer.GeneralRequest request : requestsOnboardEachVehicles.get(vehicleId)) {
                    initialRouteBuilder.addPickup(requestToShipmentMap.get(request));
                }
                // Then add each stops according to the previous plan
                for (TimetableEntry stop : previousSchedules.vehicleToTimetableMap().get(vehicleId)) {
                    Shipment shipment = requestToShipmentMap.get(stop.getRequest());
                    if (stop.stopType == TimetableEntry.StopType.PICKUP) {
                        initialRouteBuilder.addPickup(shipment);
                    } else {
                        initialRouteBuilder.addDelivery(shipment);
                    }
                }
                // Build the route and store in the map
                VehicleRoute iniRoute = initialRouteBuilder.build();
                routesForInitialSolutions.add(iniRoute);
            }
        }
        VehicleRoutingProblemSolution initialSolution = new VehicleRoutingProblemSolution(routesForInitialSolutions, unassignedShipments, 0);
        initialSolution.setCost(new DefaultRollingHorizonObjectiveFunction(problem).getCosts(initialSolution));

        var algorithm = Jsprit.Builder.newInstance(problem)
                .setProperty(Jsprit.Parameter.THREADS, numOfThreads)
                .setObjectiveFunction(new DefaultRollingHorizonObjectiveFunction(problem))
                .setRandom(options.random)
                .buildAlgorithm();
        algorithm.setMaxIterations(options.maxIterations);
        algorithm.addInitialSolution(initialSolution);
        var solutions = algorithm.searchSolutions();
        var bestSolution = Solutions.bestOf(solutions);

        // Collect results
        Set<Id<Person>> personsOnboard = new HashSet<>();
        requestsOnboardEachVehicles.values().forEach(l -> l.forEach(r -> personsOnboard.add(r.passengerId())));

        Map<Id<Person>, Id<DvrpVehicle>> assignedPassengerToVehicleMap = new HashMap<>();
        Map<Id<DvrpVehicle>, List<TimetableEntry>> vehicleToPreplannedStops = problem.getVehicles()
                .stream()
                .collect(Collectors.toMap(v -> Id.create(v.getId(), DvrpVehicle.class), v -> new LinkedList<>()));

        for (var route : bestSolution.getRoutes()) {
            var vehicleId = Id.create(route.getVehicle().getId(), DvrpVehicle.class);
            DvrpVehicle vehicle = onlineVehicleInfoMap.get(vehicleId).vehicle();
            int occupancy = 0;
            for (var activity : route.getActivities()) {
                var preplannedRequest = preplannedRequestByShipmentId.get(((TourActivity.JobActivity) activity).getJob().getId());
                boolean isPickup = activity instanceof PickupShipment;
                if (isPickup) {
                    if (!personsOnboard.contains(preplannedRequest.passengerId())) {
                        // Add pick up stop if passenger is not yet onboard
                        var preplannedStop = new TimetableEntry(preplannedRequest, TimetableEntry.StopType.PICKUP,
                                activity.getArrTime(), activity.getEndTime(), occupancy, drtCfg.stopDuration, vehicle);
                        vehicleToPreplannedStops.get(vehicleId).add(preplannedStop);
                    }
                    assignedPassengerToVehicleMap.put(preplannedRequest.passengerId(), vehicleId);
                    occupancy++;
                } else {
                    // Add drop off stop
                    var preplannedStop = new TimetableEntry(preplannedRequest, TimetableEntry.StopType.DROP_OFF,
                            activity.getArrTime(), activity.getEndTime(), occupancy, drtCfg.stopDuration, vehicle);
                    vehicleToPreplannedStops.get(vehicleId).add(preplannedStop);
                    occupancy--;
                }
            }
        }

        List<Id<Person>> rejectedRequests = new ArrayList<>();
        for (Job job : bestSolution.getUnassignedJobs()) {
            MixedCaseDrtOptimizer.GeneralRequest rejectedRequest = preplannedRequestByShipmentId.get(job.getId());
            rejectedRequests.add(rejectedRequest.passengerId());
        }

        return new MixedCaseDrtOptimizer.FleetSchedules(vehicleToPreplannedStops, assignedPassengerToVehicleMap, rejectedRequests);
    }

    // Inner classes / records
    record Options(int maxIterations, boolean multiThread, Random random) {
    }

    record MatrixBasedVrpCosts(TravelTimeMatrix travelTimeMatrix, double now,
                               Network network, TravelTime travelTime) implements VehicleRoutingTransportCosts {
        private double getTravelTime(Location from, Location to) {
            if (from.getId().equals(to.getId())) {
                return 0;
            }
            Link fromLink = network.getLinks().get(Id.createLinkId(from.getId()));
            Link toLink = network.getLinks().get(Id.createLinkId(to.getId()));
            return FIRST_LINK_TT + travelTimeMatrix.getTravelTime(fromLink.getToNode(), toLink.getFromNode(), now)
                    + VrpPaths.getLastLinkTT(travelTime, toLink, now);
        }

        @Override
        public double getTransportCost(Location from, Location to, double departureTime, Driver driver, Vehicle vehicle) {
            return getTravelTime(from, to);
        }

        @Override
        public double getTransportTime(Location from, Location to, double departureTime, Driver driver, Vehicle vehicle) {
            return getTravelTime(from, to);
        }

        @Override
        public double getBackwardTransportCost(Location from, Location to, double arrivalTime, Driver driver, Vehicle vehicle) {
            return getTravelTime(to, from);
        }

        @Override
        public double getBackwardTransportTime(Location from, Location to, double arrivalTime, Driver driver, Vehicle vehicle) {
            return getTravelTime(to, from);
        }

        @Override
        public double getDistance(Location from, Location to, double departureTime, Vehicle vehicle) {
            throw new RuntimeException("Get distance is not yet implemented. Use travel time or cost instead!");
        }
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
                costs += REJECTION_COST * (11 - j.getPriority()) * (11 - j.getPriority()) * (11 - j.getPriority()); // Make sure the cost to "reject" request onboard is prohibitively large
            }

            return costs;
        }
    }

    // private methods
    private Location collectLocationIfAbsent(Link link) {
        return locationByLinkId.computeIfAbsent(link.getId(), linkId -> Location.Builder.newInstance()
                .setId(link.getId() + "")
                .setIndex(locationByLinkId.size())
                .setCoordinate(Coordinate.newInstance(link.getCoord().getX(), link.getCoord().getY()))
                .build());
    }

    private TravelTimeMatrix createTravelTimeMatrix(double time) {
        Map<Node, Zone> zoneByNode = locationByLinkId.keySet()
                .stream()
                .flatMap(linkId -> Stream.of(network.getLinks().get(linkId).getFromNode(), network.getLinks().get(linkId).getToNode()))
                .collect(toMap(n -> n, node -> new Zone(Id.create(node.getId(), Zone.class), "node", node.getCoord()),
                        (zone1, zone2) -> zone1));
        var nodeByZone = EntryStream.of(zoneByNode).invert().toMap();
        Matrix nodeToNodeMatrix = TravelTimeMatrices.calculateTravelTimeMatrix(network, nodeByZone, time, travelTime,
                travelDisutility, Runtime.getRuntime().availableProcessors());

        return (fromNode, toNode, departureTime) -> nodeToNodeMatrix.get(zoneByNode.get(fromNode), zoneByNode.get(toNode));
    }

}
