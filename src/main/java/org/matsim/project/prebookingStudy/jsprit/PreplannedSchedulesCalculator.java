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
import static org.matsim.contrib.drt.extension.preplanned.optimizer.PreplannedDrtOptimizer.*;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.core.router.TripStructureUtils;

import com.google.common.base.Preconditions;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.PickupShipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.Solutions;

import one.util.streamex.StreamEx;

/**
 * @author Michal Maciejewski (michalm)
 */
public class PreplannedSchedulesCalculator {

	private final DrtConfigGroup drtCfg;
	private final FleetSpecification fleetSpecification;
	private final Network network;
	private final Population population;
	private final boolean infiniteFleet;

	private final Map<Id<Link>, Location> locationByLinkId = new IdMap<>(Link.class);

	//infinite fleet - set to false when calculating plans inside the mobsim (the fleet is finite)
	public PreplannedSchedulesCalculator(DrtConfigGroup drtCfg, FleetSpecification fleetSpecification, Network network,
			Population population, boolean infiniteFleet) {
		this.drtCfg = drtCfg;
		this.fleetSpecification = fleetSpecification;
		this.network = network;
		this.population = population;
		this.infiniteFleet = infiniteFleet;
	}

	public PreplannedSchedules calculate() {
		var vrpBuilder = new Builder();

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
		if (infiniteFleet) {
			dvrpVehicles = StreamEx.of(dvrpVehicles).distinct(DvrpVehicleSpecification::getStartLinkId);
		}

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

		var preplannedRequestByShipmentId = new HashMap<String, PreplannedRequest>();
		// create shipments
		for (Person person : population.getPersons().values()) {
			for (var leg : TripStructureUtils.getLegs(person.getSelectedPlan())) {
				if (!leg.getMode().equals(drtCfg.getMode())) {
					continue;
				}
				var startLink = network.getLinks().get(leg.getRoute().getStartLinkId());
				var pickupLocation = locationByLinkId.get(startLink.getId());

				var endLink = network.getLinks().get(leg.getRoute().getEndLinkId());
				var dropoffLocation = locationByLinkId.get(endLink.getId());

				double earliestPickupTime = leg.getDepartureTime().seconds();
				double latestPickupTime = earliestPickupTime + drtCfg.getMaxWaitTime();
				double travelTime = vrpCosts.getTransportTime(pickupLocation, dropoffLocation, earliestPickupTime, null,
						null);

				double earliestDeliveryTime = earliestPickupTime + travelTime;
				double latestDeliveryTime = earliestPickupTime
						+ travelTime * drtCfg.getMaxTravelTimeAlpha()
						+ drtCfg.getMaxTravelTimeBeta();

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
						.setPickupServiceTime(0)
						.setDeliveryServiceTime(0)
						.setPickupTimeWindow(new TimeWindow(earliestPickupTime, latestPickupTime))
						.setDeliveryTimeWindow(new TimeWindow(earliestDeliveryTime, latestDeliveryTime))
						.addSizeDimension(0, 1)
						.build();
				vrpBuilder.addJob(shipment);

				// shipment -> preplanned request
				var preplannedRequest = new PreplannedRequest(person.getId(), earliestPickupTime, latestPickupTime,
						latestDeliveryTime, startLink.getId(), endLink.getId());
				preplannedRequestByShipmentId.put(shipmentId, preplannedRequest);
			}
		}

		// run jsprit
		var problem = vrpBuilder.setFleetSize(infiniteFleet ? FleetSize.INFINITE : FleetSize.FINITE).build();
		var algorithm = Jsprit.Builder.newInstance(problem)
				.setProperty(Jsprit.Parameter.THREADS, Runtime.getRuntime().availableProcessors() + "")
				.buildAlgorithm();
		algorithm.setMaxIterations(1);
		var solutions = algorithm.searchSolutions();
		var bestSolution = Solutions.bestOf(solutions);
		SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);

		Map<PreplannedRequest, Id<DvrpVehicle>> preplannedRequestToVehicle = new HashMap<>();
		Map<Id<DvrpVehicle>, Queue<PreplannedStop>> vehicleToPreplannedStops = problem.getVehicles()
				.stream()
				.collect(Collectors.toMap(v -> Id.create(v.getId(), DvrpVehicle.class), v -> new LinkedList<>()));

		for (var route : bestSolution.getRoutes()) {
			var vehicleId = Id.create(route.getVehicle().getId(), DvrpVehicle.class);
			for (var activity : route.getActivities()) {
				var jobActivity = (TourActivity.JobActivity)activity;
				var preplannedRequest = preplannedRequestByShipmentId.get(jobActivity.getJob().getId());
				preplannedRequestToVehicle.put(preplannedRequest, vehicleId);

				//act -> preplanned stop
				var preplannedStop = new PreplannedStop(preplannedRequest, jobActivity instanceof PickupShipment);
				vehicleToPreplannedStops.get(vehicleId).add(preplannedStop);
			}
		}

		return new PreplannedSchedules(preplannedRequestToVehicle, vehicleToPreplannedStops);

	}

	private Location collectLocationIfAbsent(Link link) {
		return locationByLinkId.computeIfAbsent(link.getId(), linkId -> Location.Builder.newInstance()
				.setId(link.getId() + "")
				.setIndex(locationByLinkId.size())
				.setCoordinate(Coordinate.newInstance(link.getCoord().getX(), link.getCoord().getY()))
				.build());
	}
}
