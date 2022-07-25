package org.matsim.project.drtSchoolTransportStudy.run.dummyTraffic;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

import javax.inject.Inject;

public class QSimFreeSpeedTravelTimeFixed implements TravelTime {
    private final double timeStepSize;
    private final double travelTimeOverEstimation;

    @Inject
    public QSimFreeSpeedTravelTimeFixed(QSimConfigGroup qsimCfg) {
        this(qsimCfg.getTimeStepSize());
    }

    public QSimFreeSpeedTravelTimeFixed(double timeStepSize) {
        this.timeStepSize = timeStepSize;
        this.travelTimeOverEstimation = 0.0;
    }

    public QSimFreeSpeedTravelTimeFixed(double timeStepSize, double travelTimeOverEstimation) {
        this.timeStepSize = timeStepSize;
        this.travelTimeOverEstimation = travelTimeOverEstimation;

    }

    @Override
    public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
        double freeSpeedTT = link.getLength() / link.getFreespeed(0);
        // In this study, we don't have network change event at time = 0 --> initial free speed of network  //TODO perhaps read this from input network file
        double linkTravelTime = timeStepSize * Math.floor(freeSpeedTT / timeStepSize) + timeStepSize;
        return (1 + travelTimeOverEstimation) * linkTravelTime;
    }
}
