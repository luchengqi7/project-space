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

package org.matsim.project.drtSchoolTransportStudy.jsprit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.extension.preplanned.run.PreplannedDrtControlerCreator;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.otfvis.OTFVisLiveModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.project.drtSchoolTransportStudy.analysis.SchoolTripsAnalysis;
import org.matsim.vis.otfvis.OTFVisConfigGroup;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import static org.matsim.contrib.drt.extension.preplanned.optimizer.PreplannedDrtOptimizer.PreplannedSchedules;

/**
 * @author michal.mac
 */
public class RunPreplannedDrtExample {
    private static final Logger log = LogManager.getLogger(RunPreplannedDrtExample.class);

    public static void main(String[] args) throws IOException {
        var configFile = "scenarios/vulkaneifel-school-traffic/vulkaneifel-v1.0-school-childrem.config.xml";
        RunPreplannedDrtExample.run(IOUtils.resolveFileOrResource(configFile), false, 0);
    }

    public static void run(URL configUrl, boolean otfvis, int lastIteration) throws IOException {
        Config config = ConfigUtils.loadConfig(configUrl, new MultiModeDrtConfigGroup(), new DvrpConfigGroup(),
                new OTFVisConfigGroup());
        config.controler().setLastIteration(lastIteration);
        for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(config).getModalElements()) {
            if (drtCfg.getRebalancingParams().isPresent()) {
                log.warn("The rebalancing parameter set is defined for drt mode: "
                        + drtCfg.getMode()
                        + ". It will be ignored. No rebalancing will happen.");
                drtCfg.removeParameterSet(drtCfg.getRebalancingParams().get());
            }
        }

        Controler controler = PreplannedDrtControlerCreator.createControler(config, otfvis);

        var options = new PreplannedSchedulesCalculatorForSchoolTransport.Options(false, true, 200, true);

        // compute PreplannedSchedules before starting QSim
        MultiModeDrtConfigGroup.get(config)
                .getModalElements()
                .forEach(drtConfig -> controler.addOverridingQSimModule(
                        new AbstractDvrpModeQSimModule(drtConfig.getMode()) {
                            @Override
                            protected void configureQSim() {
                                bindModal(PreplannedSchedules.class).toProvider(modalProvider(
                                        getter -> {
                                            try {
                                                return new PreplannedSchedulesCalculatorForSchoolTransport(drtConfig,
                                                        getter.getModal(FleetSpecification.class),
                                                        getter.getModal(Network.class), getter.get(Population.class),
                                                        options).calculate();
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                            return null;
                                        })).asEagerSingleton();
                            }
                        }));

        if (otfvis) {
            controler.addOverridingModule(new OTFVisLiveModule());
        }

        controler.run();
        new SchoolTripsAnalysis().analyze(Path.of(config.controler().getOutputDirectory()));
    }
}
