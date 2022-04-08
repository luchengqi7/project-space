/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2018 by the members listed in the COPYING,        *
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

import static org.matsim.contrib.drt.extension.preplanned.optimizer.PreplannedDrtOptimizer.PreplannedSchedules;

import java.net.URL;

import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.extension.preplanned.run.PreplannedDrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.otfvis.OTFVisLiveModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.vis.otfvis.OTFVisConfigGroup;

/**
 * @author michal.mac
 */
public class RunPreplannedDrtExample {
	public static void main(String[] args) {
		var configFile = "scenarios/vulkaneifel-school-traffic/vulkaneifel-v1.0-school-childrem.config.xml";
		RunPreplannedDrtExample.run(IOUtils.resolveFileOrResource(configFile), false, 0);
	}

	public static void run(URL configUrl, boolean otfvis, int lastIteration) {
		Config config = ConfigUtils.loadConfig(configUrl, new MultiModeDrtConfigGroup(), new DvrpConfigGroup(),
				new OTFVisConfigGroup());
		config.controler().setLastIteration(lastIteration);

		Controler controler = PreplannedDrtControlerCreator.createControler(config, otfvis);

		var options = new PreplannedSchedulesCalculator.Options(false, true, 200);

		// compute PreplannedSchedules before starting QSim
		MultiModeDrtConfigGroup.get(config)
				.getModalElements()
				.forEach(drtConfig -> controler.addOverridingQSimModule(
						new AbstractDvrpModeQSimModule(drtConfig.getMode()) {
							@Override
							protected void configureQSim() {
								bindModal(PreplannedSchedules.class).toProvider(modalProvider(
										getter -> new PreplannedSchedulesCalculator(drtConfig,
												getter.getModal(FleetSpecification.class),
												getter.getModal(Network.class), getter.get(Population.class),
												options).calculate())).asEagerSingleton();
							}
						}));

		if (otfvis) {
			controler.addOverridingModule(new OTFVisLiveModule());
		}

		controler.run();
	}
}
