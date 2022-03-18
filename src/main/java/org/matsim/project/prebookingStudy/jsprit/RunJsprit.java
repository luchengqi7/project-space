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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
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
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.Solutions;

/**
 * @author Michal Maciejewski (michalm)
 */
public class RunJsprit {
	private final Map<Id<Node>, Location> locationByNodeId = new IdMap<>(Node.class);

	public static void main(String[] args) {
		//		new RunJsprit().runJsprit("D:\\matsim-intelliJ\\luchengqi7_project-space\\scenarios\\vulkaneifel\\config.xml");
		new RunJsprit().runJsprit(
				"D:\\matsim-intelliJ\\luchengqi7_project-space\\scenarios\\vulkaneifel-school-traffic\\vulkaneifel-v1.0-school-childrem.config.xml");
	}

	void runJsprit(String matsimConfig) {
		var config = ConfigUtils.loadConfig(matsimConfig, new MultiModeDrtConfigGroup());
		var scenario = ScenarioUtils.loadScenario(config);
		var network = scenario.getNetwork();

		Preconditions.checkState(MultiModeDrtConfigGroup.get(config).getModalElements().size() == 1);
		var drtCfg = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();

		var vrpBuilder = new VehicleRoutingProblem.Builder();

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

		// let's use one link for all vehicles instead of the start links from the dvrp vehicle file
		var startLinkId = Id.createLinkId("415872430048f");
		var startNode = scenario.getNetwork().getLinks().get(startLinkId).getToNode();
		var startLocation = computeLocationIfAbsent(startNode);

		for (DvrpVehicleSpecification dvrpVehicle : dvrpFleetSpecification.getVehicleSpecifications().values()) {
			// var startLinkId = dvrpVehicle.getStartLinkId();
			var vehicleBuilder = VehicleImpl.Builder.newInstance(dvrpVehicle.getId() + "");
			vehicleBuilder.setStartLocation(startLocation);
			vehicleBuilder.setEarliestStart(dvrpVehicle.getServiceBeginTime());
			vehicleBuilder.setLatestArrival(dvrpVehicle.getServiceEndTime());
			vehicleBuilder.setType(vehicleType);

			vrpBuilder.addVehicle(vehicleBuilder.build());
		}

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

				double earliestPickupTime = originActivity.getEndTime().seconds();
				double latestPickupTime = earliestPickupTime + drtCfg.getMaxWaitTime();
				double travelTime = vrpCosts.getTransportTime(originLocation, destinationLocation, earliestPickupTime,
						null, null);
				double earliestDeliveryTime = earliestPickupTime + travelTime;
				double latestDeliveryTime = earliestPickupTime
						+ travelTime * drtCfg.getMaxTravelTimeAlpha()
						+ drtCfg.getMaxTravelTimeBeta();

				var shipment = Shipment.Builder.newInstance(person.getId() + "")
						.setPickupLocation(originLocation)
						.setDeliveryLocation(destinationLocation)
						.setPickupServiceTime(0)
						.setDeliveryServiceTime(0)
						.setPickupTimeWindow(new TimeWindow(earliestPickupTime, latestPickupTime))
						.setDeliveryTimeWindow(new TimeWindow(earliestDeliveryTime, latestDeliveryTime))
						.addSizeDimension(0, 1)
						.build();
				vrpBuilder.addJob(shipment);
			}
		}

		// run jsprit
		var problem = vrpBuilder.build();
		var algorithm = Jsprit.Builder.newInstance(problem)
				.setProperty(Jsprit.Parameter.THREADS, Runtime.getRuntime().availableProcessors()+"")
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
}
