package org.matsim.playground.germanFreight;

import org.matsim.application.MATSimApplication;
import org.matsim.application.prepare.freight.optimization.DetermineAverageTruckLoad;

import java.net.MalformedURLException;

public class OptimizationAgainstTrafficCount extends MATSimApplication {
    public static void main(String[] args) throws MalformedURLException {
        DetermineAverageTruckLoad.main(args);
    }
}
