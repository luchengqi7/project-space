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

import static java.util.stream.Collectors.toMap;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.contrib.zone.Zone;
import org.matsim.contrib.zone.skims.Matrix;
import org.matsim.contrib.zone.skims.TravelTimeMatrices;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;

import one.util.streamex.EntryStream;

/**
 * @author Michal Maciejewski (michalm)
 */
public class MatrixBasedVrpCosts implements VehicleRoutingTransportCosts {
	public static MatrixBasedVrpCosts calculateVrpCosts(Network network, Map<Id<Node>, Location> locationByNodeId) {
		var nodeByLocationIndex = EntryStream.of(locationByNodeId)
				.invert()
				.mapKeys(Location::getIndex)
				.mapValues(nodeId -> (Node)network.getNodes().get(nodeId))
				.toMap();
		var zoneByNode = nodeByLocationIndex.values()
				.stream()
				.collect(toMap(n -> n, node -> new Zone(Id.create(node.getId(), Zone.class), "node", node.getCoord())));
		var nodeByZone = EntryStream.of(zoneByNode).invert().toMap();

		var travelTime = new QSimFreeSpeedTravelTime(1);
		var travelDisutility = new TimeAsTravelDisutility(travelTime);
		var matrix = TravelTimeMatrices.calculateTravelTimeMatrix(network, nodeByZone, 0, travelTime, travelDisutility,
				12);

		var locationZones = new Zone[locationByNodeId.size() + 1];
		nodeByLocationIndex.forEach((index, node) -> locationZones[index] = zoneByNode.get(node));

		return new MatrixBasedVrpCosts(matrix, locationZones);
	}

	private final Matrix matrix;
	private final Zone[] locationZones;

	private MatrixBasedVrpCosts(Matrix matrix, Zone[] locationZones) {
		this.matrix = matrix;
		this.locationZones = locationZones;
	}

	private double getTravelTime(Location from, Location to) {
		var fromZone = locationZones[from.getIndex()];
		var toZone = locationZones[to.getIndex()];
		return matrix.get(fromZone, toZone);
	}

	@Override
	public double getBackwardTransportCost(Location from, Location to, double arrivalTime, Driver driver,
			Vehicle vehicle) {
		return getTravelTime(from, to);
	}

	@Override
	public double getBackwardTransportTime(Location from, Location to, double arrivalTime, Driver driver,
			Vehicle vehicle) {
		return getTravelTime(from, to);
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
	public double getDistance(Location from, Location to, double departureTime, Vehicle vehicle) {
		return getTravelTime(from, to);
	}
}
