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

package org.matsim.project.drtSchoolTransportStudy.jsprit;

import static java.util.stream.Collectors.toMap;
import static org.matsim.contrib.dvrp.path.VrpPaths.FIRST_LINK_TT;

import java.util.Map;
import java.util.stream.Stream;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.contrib.zone.Zone;
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
	public static MatrixBasedVrpCosts calculateVrpCosts(Network network, Map<Id<Link>, Location> locationByLinkId) {
		var linkByLocationIndex = EntryStream.of(locationByLinkId)
				.invert()
				.mapKeys(Location::getIndex)
				.mapValues(linkId -> (Link)network.getLinks().get(linkId))
				.toMap();

		var zoneByNode = linkByLocationIndex.values()
				.stream()
				.flatMap(link -> Stream.of(link.getFromNode(), link.getToNode()))
				.collect(toMap(n -> n, node -> new Zone(Id.create(node.getId(), Zone.class), "node", node.getCoord()),
						(zone1, zone2) -> zone1));

		var nodeByZone = EntryStream.of(zoneByNode).invert().toMap();

		// compute node-to-node TT matrix
		var travelTime = new QSimFreeSpeedTravelTime(1);
		var travelDisutility = new TimeAsTravelDisutility(travelTime);
		var nodeToNodeMatrix = TravelTimeMatrices.calculateTravelTimeMatrix(network, nodeByZone, 0, travelTime,
				travelDisutility, Runtime.getRuntime().availableProcessors());

		int size = locationByLinkId.size();
		int[][] travelTimes = new int[size][size];

		for (var from : locationByLinkId.entrySet()) {
			var fromLink = network.getLinks().get(from.getKey());
			var fromZone = zoneByNode.get(fromLink.getToNode()); // we start from the link's TO node
			var fromLocationIdx = from.getValue().getIndex();

			for (var to : locationByLinkId.entrySet()) {
				var toLink = network.getLinks().get(to.getKey());
				var toZone = zoneByNode.get(toLink.getFromNode()); // we finish at the link's FROM node
				var toLocationIdx = to.getValue().getIndex();

				if (fromLink != toLink) { // otherwise, the matrix cell remains set to 0
					double duration = FIRST_LINK_TT + nodeToNodeMatrix.get(fromZone, toZone) + VrpPaths.getLastLinkTT(
							travelTime, toLink, 0);
					travelTimes[fromLocationIdx][toLocationIdx] = (int)duration;
				}
			}
		}

		return new MatrixBasedVrpCosts(travelTimes);
	}

	private final int[][] travelTimes;

	private MatrixBasedVrpCosts(int[][] travelTimes) {
		this.travelTimes = travelTimes;
	}

	private double getTravelTime(Location from, Location to) {
		return travelTimes[from.getIndex()][to.getIndex()];
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
		return getTravelTime(from, to);  //TODO
	}
}
