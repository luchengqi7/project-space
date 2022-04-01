/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2022 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** *
 */

package org.matsim.project.prebookingStudy.jsprit;

import static com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.Builder;
import static com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.FleetSize;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.common.util.DistanceUtils;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetReader;
import org.matsim.contrib.dvrp.fleet.FleetSpecificationImpl;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

import com.google.common.base.Preconditions;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.Solutions;

import one.util.streamex.StreamEx;
import picocli.CommandLine;

/**
 * @author Michal Maciejewski (michalm)， modified by Chengqi Lu (luchengqi7)
 */
@CommandLine.Command(
        name = "run-jsprit",
        description = "run Jsprit scenario"
)
public class RunJsprit implements MATSimAppCommand {
    private final Map<Id<Node>, Location> locationByNodeId = new IdMap<>(Node.class);

    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--infinite-fleet", description = "path to config file", defaultValue = "true")
    private boolean infiniteFleetSize;

    @CommandLine.Option(names = "--write-optimization-progress", description = "path to config file", defaultValue = "false")
    private boolean printProgressStatistics;

    @Override
    public Integer call() throws Exception {
        runJsprit(configPath, infiniteFleetSize, printProgressStatistics);
        return 0;
    }

    public static void main(String[] args) {
        new RunJsprit().execute(args);
    }

    public void runJsprit(String matsimConfig, boolean infiniteFleet, boolean printProgressStatistics) {
        var config = ConfigUtils.loadConfig(matsimConfig, new MultiModeDrtConfigGroup());
        var scenario = ScenarioUtils.loadScenario(config);
        var network = scenario.getNetwork();

        Preconditions.checkState(MultiModeDrtConfigGroup.get(config).getModalElements().size() == 1);
        var drtCfg = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();

        var vrpBuilder = new Builder();

        // create fleet
        var dvrpFleetSpecification = new FleetSpecificationImpl();
        new FleetReader(dvrpFleetSpecification).parse(drtCfg.getVehiclesFileUrl(scenario.getConfig().getContext()));

        var capacities = dvrpFleetSpecification.getVehicleSpecifications()
                .values()
                .stream()
                .map(DvrpVehicleSpecification::getCapacity)
                .collect(Collectors.toSet());
        Preconditions.checkState(capacities.size() == 1);
        var vehicleCapacity = capacities.iterator().next();
        var vehicleType = VehicleTypeImpl.Builder.newInstance(drtCfg.getMode() + "-vehicle")
                .addCapacityDimension(0, vehicleCapacity)
                .build();

        var dvrpVehicles = dvrpFleetSpecification.getVehicleSpecifications().values().stream();
        if (infiniteFleet) {
            dvrpVehicles = StreamEx.of(dvrpVehicles).distinct(DvrpVehicleSpecification::getStartLinkId);
        }

        dvrpVehicles.forEach(dvrpVehicle -> {
            var startLinkId = dvrpVehicle.getStartLinkId();
            var startNode = scenario.getNetwork().getLinks().get(startLinkId).getToNode();
            var startLocation = computeLocationIfAbsent(startNode);
            var vehicleBuilder = VehicleImpl.Builder.newInstance(dvrpVehicle.getId() + "");
            vehicleBuilder.setStartLocation(startLocation);
            vehicleBuilder.setEarliestStart(dvrpVehicle.getServiceBeginTime());
            vehicleBuilder.setLatestArrival(dvrpVehicle.getServiceEndTime());
            vehicleBuilder.setType(vehicleType);

            vrpBuilder.addVehicle(vehicleBuilder.build());
        });

        // collect pickup/dropoff locations
        for (Person person : scenario.getPopulation().getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                Preconditions.checkState(trip.getLegsOnly().size() == 1, "A trip has more than one leg.");

                var originActivity = trip.getOriginActivity();
                var originNode = NetworkUtils.getNearestLink(network, originActivity.getCoord()).getToNode();
                computeLocationIfAbsent(originNode);

                var destinationActivity = trip.getDestinationActivity();
                var destinationNode = NetworkUtils.getNearestLink(network, destinationActivity.getCoord()).getToNode();
                computeLocationIfAbsent(destinationNode);
            }
        }

        // compute matrix
        var vrpCosts = MatrixBasedVrpCosts.calculateVrpCosts(network, locationByNodeId);
        vrpBuilder.setRoutingCost(vrpCosts);

        // create shipments
        for (Person person : scenario.getPopulation().getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                var originActivity = trip.getOriginActivity();
                var originNode = NetworkUtils.getNearestLink(network, originActivity.getCoord()).getToNode();
                var originLocation = locationByNodeId.get(originNode.getId());

                var destinationActivity = trip.getDestinationActivity();
                var destinationNode = NetworkUtils.getNearestLink(network, destinationActivity.getCoord()).getToNode();
                var destinationLocation = locationByNodeId.get(destinationNode.getId());

                if (originNode.getId().toString().equals(destinationNode.getId().toString())) {
                    continue;  // If the departure location and destination location of the DRT trips is the same, then skip
                }

                double walkingDistance = DistanceUtils.calculateDistance(originNode.getCoord(), originActivity.getCoord());
                double walkingSpeed = config.plansCalcRoute().getTeleportedModeSpeeds().get(TransportMode.walk);
                double distanceFactor = config.plansCalcRoute().getBeelineDistanceFactors().get(TransportMode.walk);
                double walkingTime = walkingDistance * distanceFactor / walkingSpeed;

                double earliestPickupTime = originActivity.getEndTime().seconds() + walkingTime;
                double latestDeliveryTime = destinationActivity.getStartTime().seconds();

                var shipment = Shipment.Builder.newInstance(person.getId() + "")
                        .setPickupLocation(originLocation)
                        .setDeliveryLocation(destinationLocation)
                        .setPickupServiceTime(1) //TODO change this value after the drt stopping time in MATSim has been updated
                        .setDeliveryServiceTime(1)
                        .setPickupTimeWindow(new TimeWindow(earliestPickupTime, latestDeliveryTime))
                        .setDeliveryTimeWindow(new TimeWindow(earliestPickupTime, latestDeliveryTime))
                        .addSizeDimension(0, 1)
                        .build();
                vrpBuilder.addJob(shipment);
            }
        }

        // run jsprit
        var problem = vrpBuilder.setFleetSize(infiniteFleet ? FleetSize.INFINITE : FleetSize.FINITE).build();
        var algorithm = Jsprit.Builder.newInstance(problem)
                .setObjectiveFunction(new SchoolTrafficObjectiveFunction(problem, infiniteFleet, printProgressStatistics))
                .setProperty(Jsprit.Parameter.THREADS, Runtime.getRuntime().availableProcessors() + "")
                .buildAlgorithm();
        algorithm.setMaxIterations(200);
        var solutions = algorithm.searchSolutions();
        var bestSolution = Solutions.bestOf(solutions);
        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);
    }

    private Location computeLocationIfAbsent(Node node) {
        return locationByNodeId.computeIfAbsent(node.getId(), nodeId -> Location.Builder.newInstance()
                .setId(node.getId() + "")
                .setIndex(locationByNodeId.size())
                .setCoordinate(Coordinate.newInstance(node.getCoord().getX(), node.getCoord().getY()))
                .build());
    }

    private static class SchoolTrafficObjectiveFunction implements SolutionCostCalculator {
        private final double unassignedPenalty = 10000; // Most important objective
        private final double costPerVehicle = 200; // Second most important objective
        private final double drivingCostPerHour = 6.0; // Less important objective
        private final boolean infiniteVehicle;
        private final VehicleRoutingProblem problem;
        private final boolean printProgressStatistics;

        SchoolTrafficObjectiveFunction(VehicleRoutingProblem problem, boolean infiniteVehicle, boolean printProgressStatistics) {
            this.infiniteVehicle = infiniteVehicle;
            this.problem = problem;
            this.printProgressStatistics = printProgressStatistics;
        }

        @Override
        public double getCosts(VehicleRoutingProblemSolution solution) {
            double numUnassignedJobs = solution.getUnassignedJobs().size();
            double costForUnassignedRequests = numUnassignedJobs * unassignedPenalty;

            double numVehiclesUsed = solution.getRoutes().size();
            double costForFleet = numVehiclesUsed * costPerVehicle;
            if (!infiniteVehicle) {
                costForFleet = 0;
            }

            VehicleRoutingTransportCosts costMatrix = problem.getTransportCosts();
            double totalTransportCost = 0;
            for (VehicleRoute route : solution.getRoutes()) {
                TourActivity prevAct = route.getStart();
                for (TourActivity activity : route.getActivities()) {
                    totalTransportCost += costMatrix.getTransportCost(prevAct.getLocation(), activity.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    prevAct = activity;
                }
            }

            totalTransportCost = totalTransportCost / 3600 * drivingCostPerHour;  // In current setup, transport cost = driving time
            double totalCost = costForUnassignedRequests + costForFleet + totalTransportCost;
            if (printProgressStatistics) {
                System.out.println("Number of unassigned jobs: " + numUnassignedJobs);
                System.out.println("Number of vehicles used: " + numVehiclesUsed);
                System.out.println("Transport cost of the whole fleet: " + totalTransportCost);
                System.out.println("Total cost = " + totalCost);
            }
            return totalCost;
        }
    }
}
