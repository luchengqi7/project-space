package org.matsim.project.drtOperationStudy.vrpSolver;

import com.google.common.base.Preconditions;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
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
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.extension.preplanned.optimizer.PreplannedDrtOptimizer;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.project.drtSchoolTransportStudy.jsprit.MatrixBasedVrpCosts;

import java.util.*;
import java.util.stream.Collectors;

public class JspritOfflineCalculatorForExperiments {
    public record Options(boolean printProgressStatistics, int maxIterations, boolean multiThread, Random random) {

    }


    public record VrpCommonPartRecord(VehicleRoutingProblem problem, VehicleRoutingAlgorithm algorithm,
                                      VehicleRoutingProblemSolution initialSolution,
                                      Map<String, PreplannedDrtOptimizer.PreplannedRequest> preplannedRequestByShipmentId,
                                      long initializationTime) {

    }

    public static class VrpCommonPart {
        public VrpCommonPartRecord vrpCommonPartRecord = null;

        public void setVrpCommonPartRecord(VrpCommonPartRecord vrpCommonPartRecord) {
            this.vrpCommonPartRecord = vrpCommonPartRecord;
        }
    }

    private final DrtConfigGroup drtCfg;
    private final FleetSpecification fleetSpecification;
    private final Network network;
    private final Population population;
    private final Options options;

    private VehicleRoutingAlgorithm algorithm;
    private VehicleRoutingProblemSolution initialSolution;
    private VehicleRoutingProblem problem;
    private Map<String, PreplannedDrtOptimizer.PreplannedRequest> preplannedRequestByShipmentId = new HashMap<>();

    private final Map<Id<Link>, Location> locationByLinkId = new IdMap<>(Link.class);

    //infinite fleet - set to false when calculating plans inside the mobsim (the fleet is finite)
    public JspritOfflineCalculatorForExperiments(DrtConfigGroup drtCfg, FleetSpecification fleetSpecification, Network network,
                                                 Population population, Options options) {
        this.drtCfg = drtCfg;
        this.fleetSpecification = fleetSpecification;
        this.network = network;
        this.population = population;
        this.options = options;
    }

    public PreplannedDrtOptimizer.PreplannedSchedules calculate(VrpCommonPart vrpCommonPart) {

        if (vrpCommonPart.vrpCommonPartRecord == null) {
            long startTime = System.currentTimeMillis();
            initializeVrp();
            long endTime = System.currentTimeMillis();
            long timeUsed = (endTime - startTime) / 1000;
            vrpCommonPart.setVrpCommonPartRecord(new VrpCommonPartRecord(problem, algorithm, initialSolution, preplannedRequestByShipmentId, timeUsed));
        }

        this.initialSolution = vrpCommonPart.vrpCommonPartRecord.initialSolution;
        this.problem = vrpCommonPart.vrpCommonPartRecord.problem;
        this.preplannedRequestByShipmentId = vrpCommonPart.vrpCommonPartRecord.preplannedRequestByShipmentId;

        String numOfThreads = "1";
        if (options.multiThread) {
            numOfThreads = Runtime.getRuntime().availableProcessors() + "";
        }

        this.algorithm = Jsprit.Builder.newInstance(problem)
                .setProperty(Jsprit.Parameter.THREADS, numOfThreads)
                .setRandom(options.random)
                .buildAlgorithm();
        algorithm.setMaxIterations(options.maxIterations);
        algorithm.addInitialSolution(initialSolution);

        var solutions = algorithm.searchSolutions();
        var bestSolution = Solutions.bestOf(solutions);
        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);

        Map<PreplannedDrtOptimizer.PreplannedRequestKey, Id<DvrpVehicle>> preplannedRequestToVehicle = new HashMap<>();
        Map<Id<DvrpVehicle>, Queue<PreplannedDrtOptimizer.PreplannedStop>> vehicleToPreplannedStops = problem.getVehicles()
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

                //act -> preplanned stop
                var preplannedStop = new PreplannedDrtOptimizer.PreplannedStop(preplannedRequest, isPickup);
                vehicleToPreplannedStops.get(vehicleId).add(preplannedStop);
            }
        }

        Map<PreplannedDrtOptimizer.PreplannedRequestKey, PreplannedDrtOptimizer.PreplannedRequest> unassignedRequests = new HashMap<>();
        for (Job job : bestSolution.getUnassignedJobs()) {
            PreplannedDrtOptimizer.PreplannedRequest rejectedRequest = preplannedRequestByShipmentId.get(job.getId());
            unassignedRequests.put(rejectedRequest.key(), rejectedRequest);
        }

        return new PreplannedDrtOptimizer.PreplannedSchedules(preplannedRequestToVehicle, vehicleToPreplannedStops, unassignedRequests);

    }

    private void initializeVrp() {
        var vrpBuilder = new VehicleRoutingProblem.Builder();

        // create fleet
        var capacities = fleetSpecification.getVehicleSpecifications()
                .values()
                .stream()
                .map(DvrpVehicleSpecification::getCapacity)
                .collect(Collectors.toSet());
        Preconditions.checkState(capacities.size() == 1);
        var vehicleCapacity = capacities.iterator().next();
        var vehicleType = VehicleTypeImpl.Builder.newInstance(drtCfg.getMode() + "-vehicle")
                .addCapacityDimension(0, vehicleCapacity)
                .build();

        var dvrpVehicles = fleetSpecification.getVehicleSpecifications().values().stream();

        dvrpVehicles.forEach(dvrpVehicle -> {
            var startLinkId = dvrpVehicle.getStartLinkId();
            var startLink = network.getLinks().get(startLinkId);
            var startLocation = collectLocationIfAbsent(startLink);
            var vehicleBuilder = VehicleImpl.Builder.newInstance(dvrpVehicle.getId() + "");
            vehicleBuilder.setStartLocation(startLocation);
            vehicleBuilder.setEarliestStart(dvrpVehicle.getServiceBeginTime());
            vehicleBuilder.setLatestArrival(dvrpVehicle.getServiceEndTime());
            vehicleBuilder.setType(vehicleType);

            vrpBuilder.addVehicle(vehicleBuilder.build());
        });

        // collect pickup/dropoff locations
        for (Person person : population.getPersons().values()) {
            for (var leg : TripStructureUtils.getLegs(person.getSelectedPlan())) {
                if (!leg.getMode().equals(drtCfg.getMode())) {
                    continue;
                }

                var startLink = network.getLinks().get(leg.getRoute().getStartLinkId());
                collectLocationIfAbsent(startLink);

                var endLink = network.getLinks().get(leg.getRoute().getEndLinkId());
                collectLocationIfAbsent(endLink);
            }
        }

        // compute matrix
        var vrpCosts = MatrixBasedVrpCosts.calculateVrpCosts(network, locationByLinkId);
        vrpBuilder.setRoutingCost(vrpCosts);

        // create shipments
        for (Person person : population.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            assert trips.size() == 1;  // For this study, there is only 1 trip per student
            String destinationActivityType = trips.get(0).getDestinationActivity().getType();
            for (var leg : TripStructureUtils.getLegs(person.getSelectedPlan())) {
                if (!leg.getMode().equals(drtCfg.getMode())) {
                    continue;
                }
                var startLink = network.getLinks().get(leg.getRoute().getStartLinkId());
                var pickupLocation = locationByLinkId.get(startLink.getId());

                var endLink = network.getLinks().get(leg.getRoute().getEndLinkId());
                var dropoffLocation = locationByLinkId.get(endLink.getId());

                double earliestPickupTime = leg.getDepartureTime().seconds();
                double latestPickupTime = earliestPickupTime + drtCfg.maxWaitTime;
                double travelTime = vrpCosts.getTransportTime(pickupLocation, dropoffLocation, earliestPickupTime, null,
                        null);

                double earliestDeliveryTime = earliestPickupTime + travelTime;
                double latestDeliveryTime = earliestPickupTime + drtCfg.maxTravelTimeAlpha * travelTime + drtCfg.maxTravelTimeBeta;

                var shipmentId = person.getId()
                        + "_"
                        + startLink.getId()
                        + "_"
                        + endLink.getId()
                        + "_"
                        + earliestPickupTime;

                var shipment = Shipment.Builder.newInstance(shipmentId)
                        .setPickupLocation(pickupLocation)
                        .setDeliveryLocation(dropoffLocation)
                        .setPickupServiceTime(drtCfg.stopDuration)
                        .setDeliveryServiceTime(drtCfg.stopDuration)
                        .setPickupTimeWindow(new TimeWindow(earliestPickupTime, latestPickupTime))
                        .setDeliveryTimeWindow(new TimeWindow(earliestDeliveryTime, latestDeliveryTime))
                        .addSizeDimension(0, 1)
                        .build();
                vrpBuilder.addJob(shipment);

                // shipment -> preplanned request
                var preplannedRequest = new PreplannedDrtOptimizer.PreplannedRequest(
                        new PreplannedDrtOptimizer.PreplannedRequestKey(person.getId(), startLink.getId(), endLink.getId()),
                        earliestPickupTime, latestPickupTime, latestDeliveryTime);
                preplannedRequestByShipmentId.put(shipmentId, preplannedRequest);
            }
        }

        // run jsprit
        this.problem = vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE).build();

        String numOfThreads = "1";
        if (options.multiThread) {
            numOfThreads = Runtime.getRuntime().availableProcessors() + "";
        }
        this.algorithm = Jsprit.Builder.newInstance(problem)
                .setProperty(Jsprit.Parameter.THREADS, numOfThreads)
                .setRandom(options.random)
                .buildAlgorithm();
        algorithm.setMaxIterations(0);

        this.initialSolution = Solutions.bestOf(algorithm.searchSolutions());

        SolutionPrinter.print(problem, this.initialSolution, SolutionPrinter.Print.VERBOSE);
    }

    private Location collectLocationIfAbsent(Link link) {
        return locationByLinkId.computeIfAbsent(link.getId(), linkId -> Location.Builder.newInstance()
                .setId(link.getId() + "")
                .setIndex(locationByLinkId.size())
                .setCoordinate(Coordinate.newInstance(link.getCoord().getX(), link.getCoord().getY()))
                .build());
    }

}
