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

import static org.assertj.core.api.Assertions.assertThat;
import static org.matsim.examples.ExamplesUtils.getTestScenarioURL;

import java.net.URL;
import java.util.stream.Collectors;

import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.Test;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.utils.io.IOUtils;

import com.graphhopper.jsprit.core.problem.Location;

/**
 * @author Michal Maciejewski (michalm)
 */
public class MatrixBasedVrpCostsTest {
	@Test
	public void matrixVrpCostsEqualToSpeedyALT() {
		URL networkUrl = IOUtils.extendUrl(getTestScenarioURL("dvrp-grid"), "grid_network.xml");
		var network = NetworkUtils.createNetwork();
		new MatsimNetworkReader(network).parse(networkUrl);

		var travelTime = new QSimFreeSpeedTravelTime(1);
		var travelDisutility = new TimeAsTravelDisutility(travelTime);
		var speedyAlt = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);

		var indexer = new MutableInt();
		var linkIdToLocation = network.getLinks()
				.values()
				.stream()
				.collect(Collectors.toMap(Identifiable::getId, link -> Location.Builder.newInstance()
						.setId(link.getId().toString())
						.setIndex(indexer.getAndIncrement())
						.build()));

		var vrpCosts = MatrixBasedVrpCosts.calculateVrpCosts(network, linkIdToLocation);

		for (var fromLink : network.getLinks().values()) {
			var fromLocation = linkIdToLocation.get(fromLink.getId());

			for (var toLink : network.getLinks().values()) {
				var speedyPath = VrpPaths.calcAndCreatePath(fromLink, toLink, 0, speedyAlt, travelTime);
				double speedyTT = speedyPath.getTravelTime();

				var toLocation = linkIdToLocation.get(toLink.getId());
				double matrixTT = vrpCosts.getTransportTime(fromLocation, toLocation, 0, null, null);

				assertThat(matrixTT).isEqualTo(speedyTT);
			}
		}
	}
}